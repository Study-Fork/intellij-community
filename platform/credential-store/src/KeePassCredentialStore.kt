// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.credentialStore

import com.intellij.credentialStore.kdbx.IncorrectMasterPasswordException
import com.intellij.credentialStore.kdbx.KdbxPassword
import com.intellij.credentialStore.kdbx.KeePassDatabase
import com.intellij.credentialStore.kdbx.loadKdbx
import com.intellij.credentialStore.keePass.MASTER_KEY_FILE_NAME
import com.intellij.credentialStore.keePass.MasterKey
import com.intellij.credentialStore.keePass.MasterKeyFileStorage
import com.intellij.ide.passwordSafe.PasswordStorage
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.io.setOwnerPermissions
import com.intellij.util.io.delete
import com.intellij.util.io.exists
import com.intellij.util.io.writeSafe
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

private const val ROOT_GROUP_NAME = SERVICE_NAME_PREFIX

internal const val DB_FILE_NAME = "c.kdbx"

@Suppress("FunctionName")
internal fun KeePassCredentialStore(newDb: Map<CredentialAttributes, Credentials>): KeePassCredentialStore {
  val keepassDb = KeePassDatabase()
  val group = keepassDb.rootGroup.getOrCreateGroup(ROOT_GROUP_NAME)
  for ((attributes, credentials) in newDb) {
    val entry = keepassDb.createEntry(attributes.serviceName)
    entry.userName = credentials.userName
    entry.password = credentials.password?.let(::SecureString)
    group.addEntry(entry)
  }
  return KeePassCredentialStore(baseDirectory = getDefaultKeePassBaseDirectory(), preloadedDb = keepassDb)
}

internal fun getDefaultKeePassBaseDirectory() = Paths.get(PathManager.getConfigPath())

internal fun getDefaultMasterPasswordFile() = getDefaultKeePassBaseDirectory().resolve(MASTER_KEY_FILE_NAME)

internal fun createInMemoryKeePassCredentialStore(): KeePassCredentialStore {
  val baseDirectory = getDefaultKeePassBaseDirectory()
  // for now not safe to pass fake path because later KeePassCredentialStore can be transformed to not in memory
  return KeePassCredentialStore(baseDirectory.resolve(DB_FILE_NAME), baseDirectory.resolve(MASTER_KEY_FILE_NAME), isMemoryOnly = true)
}

/**
 * preloadedMasterKey [MasterKey.value] will be cleared
 */
internal class KeePassCredentialStore constructor(dbFile: Path,
                                                  internal val masterKeyFile: Path,
                                                  preloadedMasterKey: MasterKey? = null,
                                                  preloadedDb: KeePassDatabase? = null,
                                                  isMemoryOnly: Boolean = false) : PasswordStorage, CredentialStore {
  constructor(baseDirectory: Path, preloadedMasterKey: MasterKey? = null, preloadedDb: KeePassDatabase? = null) : this(dbFile = baseDirectory.resolve(DB_FILE_NAME),
                                                                                                                       masterKeyFile = baseDirectory.resolve(MASTER_KEY_FILE_NAME),
                                                                                                                       preloadedMasterKey = preloadedMasterKey,
                                                                                                                       preloadedDb = preloadedDb)

  constructor(dbFile: Path, masterKeyFile: Path) : this(dbFile = dbFile, masterKeyFile = masterKeyFile, isMemoryOnly = false)

  var isMemoryOnly = isMemoryOnly
    set(value) {
      if (field != value && !value) {
        isNeedToSave.set(true)
      }
      field = value
    }

  internal var dbFile: Path = dbFile
    set(path) {
      if (field == path) {
        return
      }

      field = path
      isNeedToSave.set(true)
      save()
    }

  private var db: KeePassDatabase

  private val masterKeyStorage by lazy { MasterKeyFileStorage(masterKeyFile) }

  private val isNeedToSave: AtomicBoolean

  init {
    if (preloadedDb == null) {
      isNeedToSave = AtomicBoolean(false)
      db = when {
        !isMemoryOnly && dbFile.exists() -> {
          val masterPassword = preloadedMasterKey?.value ?: masterKeyStorage.get() ?: throw IncorrectMasterPasswordException(isFileMissed = true)
          loadKdbx(dbFile, KdbxPassword(masterPassword))
        }
        else -> KeePassDatabase()
      }
    }
    else {
      isNeedToSave = AtomicBoolean(!isMemoryOnly)
      db = preloadedDb
    }

    if (preloadedMasterKey != null) {
      masterKeyStorage.set(preloadedMasterKey)
    }
  }

  @Synchronized
  @TestOnly
  fun reload() {
    LOG.assertTrue(!isMemoryOnly)

    val key = masterKeyStorage.get()!!
    val kdbxPassword = KdbxPassword(key)
    key.fill(0)
    db = loadKdbx(dbFile, kdbxPassword)
    isNeedToSave.set(false)
  }

  @Synchronized
  fun save() {
    if (isMemoryOnly || (!isNeedToSave.compareAndSet(true, false) && !db.isDirty)) {
      return
    }

    try {
      var masterKey = masterKeyStorage.get()
      var isSaveMasterKey = false
      if (masterKey == null) {
        val bytes = ByteArray(512)
        createSecureRandom().nextBytes(bytes)
        masterKey = Base64.getEncoder().withoutPadding().encode(bytes)
        isSaveMasterKey = true
      }

      val kdbxPassword = KdbxPassword(masterKey!!)
      if (isSaveMasterKey) {
        masterKeyStorage.set(MasterKey(masterKey, isAutoGenerated = true))
      }
      masterKey.fill(0)

      dbFile.writeSafe { db.save(kdbxPassword, it) }
      dbFile.setOwnerPermissions()
    }
    catch (e: Throwable) {
      // schedule save again
      isNeedToSave.set(true)
      LOG.error("Cannot save password database", e)
    }
  }

  @Synchronized
  fun isNeedToSave() = !isMemoryOnly && (isNeedToSave.get() || db.isDirty)

  @Synchronized
  fun deleteFileStorage() {
    try {
      dbFile.delete()
    }
    finally {
      masterKeyStorage.set(null)
    }
  }

  fun clear() {
    db.rootGroup.removeGroup(ROOT_GROUP_NAME)
    isNeedToSave.set(db.isDirty)
  }

  override fun get(attributes: CredentialAttributes): Credentials? {
    val requestor = attributes.requestor
    val userName = attributes.userName
    val entry = db.rootGroup.getGroup(ROOT_GROUP_NAME)?.getEntry(attributes.serviceName, attributes.userName)
    if (entry != null) {
      return Credentials(attributes.userName ?: entry.userName, entry.password?.get())
    }

    if (requestor == null || userName == null) {
      return null
    }

    // try old key - as hash
    val oldAttributes = toOldKey(requestor, userName)
    db.rootGroup.getGroup(ROOT_GROUP_NAME)?.removeEntry(oldAttributes.serviceName, oldAttributes.userName)?.let {
      fun createCredentials() = Credentials(userName, it.password?.get())
      set(CredentialAttributes(requestor, userName), createCredentials())
      return createCredentials()
    }

    return null
  }

  override fun set(attributes: CredentialAttributes, credentials: Credentials?) {
    if (credentials == null) {
      db.rootGroup.getGroup(ROOT_GROUP_NAME)?.removeEntry(attributes.serviceName, attributes.userName)
    }
    else {
      val group = db.rootGroup.getOrCreateGroup(ROOT_GROUP_NAME)
      // should be the only credentials per service name - find without user name
      val userName = attributes.userName ?: credentials.userName
      var entry = group.getEntry(attributes.serviceName, if (attributes.serviceName == SERVICE_NAME_PREFIX) userName else null)
      if (entry == null) {
        entry = group.getOrCreateEntry(attributes.serviceName, userName)
      }
      entry.userName = userName
      entry.password = if (attributes.isPasswordMemoryOnly || credentials.password == null) null else SecureString(credentials.password!!)
    }

    if (db.isDirty) {
      isNeedToSave.set(true)
    }
  }

  fun copyTo(store: PasswordStorage) {
    val group = db.rootGroup.getGroup(ROOT_GROUP_NAME) ?: return
    for (entry in group.entries) {
      val title = entry.title
      if (title != null) {
        store.set(CredentialAttributes(title, entry.userName), Credentials(entry.userName, entry.password?.get()))
      }
    }
  }

  /**
   * [MasterKey.value] will be cleared on set
   */
  fun setMasterPassword(masterKey: MasterKey) {
    LOG.assertTrue(!isMemoryOnly)

    // KdbxPassword hashes value, so, it can be cleared before file write (to reduce time when master password exposed in memory)
    val kdbxPassword = KdbxPassword(masterKey.value!!)
    masterKeyStorage.set(masterKey)
    dbFile.writeSafe { db.save(kdbxPassword, it) }
    dbFile.setOwnerPermissions()
  }
}

internal fun copyTo(from: Map<CredentialAttributes, Credentials>, store: PasswordStorage) {
  for ((k, v) in from) {
    store.set(k, v)
  }
}