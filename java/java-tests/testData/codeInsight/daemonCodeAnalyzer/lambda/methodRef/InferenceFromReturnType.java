class MyTestDefaultConstructor {
   static class SuperFoo<<warning descr="Type parameter 'X' is never used">X</warning>> { }

   static class Foo<X extends Number> extends SuperFoo<X> {
   }
   
   interface I1 {
       SuperFoo<String> _();
   }

   interface I2 {
       SuperFoo<Integer> _();
   }
   
   interface I3 {
       SuperFoo<Object> _();
   }
   
   private static void <warning descr="Private method 'foo(MyTestDefaultConstructor.I1)' is never used">foo</warning>(I1 i) {System.out.println(i);}
   private static void <warning descr="Private method 'foo(MyTestDefaultConstructor.I2)' is never used">foo</warning>(I2 i) {System.out.println(i);}
   private static void <warning descr="Private method 'foo(MyTestDefaultConstructor.I3)' is never used">foo</warning>(I3 i) {System.out.println(i);}

   static {
       foo<error descr="Cannot resolve method 'foo(<method reference>)'">(Foo::new)</error>;
   }
}

class MyTestConstructor {
   static class SuperFoo<<warning descr="Type parameter 'X' is never used">X</warning>> { }

   static class Foo<X extends Number> extends SuperFoo<X> {
     Foo(){}
   }
   
   interface I1 {
       SuperFoo<String> _();
   }

   interface I2 {
       SuperFoo<Integer> _();
   }
   
   interface I3 {
       SuperFoo<Object> _();
   }
   
   private static void <warning descr="Private method 'foo(MyTestConstructor.I1)' is never used">foo</warning>(I1 i) {System.out.println(i);}
   private static void <warning descr="Private method 'foo(MyTestConstructor.I2)' is never used">foo</warning>(I2 i) {System.out.println(i);}
   private static void <warning descr="Private method 'foo(MyTestConstructor.I3)' is never used">foo</warning>(I3 i) {System.out.println(i);}

   static {
       foo<error descr="Cannot resolve method 'foo(<method reference>)'">(Foo::new)</error>;
   }
}

class MyTestMethod {
    static class SuperFoo<<warning descr="Type parameter 'X' is never used">X</warning>> { }

    static class Foo<X extends Number> extends SuperFoo<X> {
    }
    
    interface I1 {
        SuperFoo<String> _();
    }

    interface I2 {
        SuperFoo<Integer> _();
    }
    
    interface I3 {
        SuperFoo<Object> _();
    }
    
    static <X extends Number> Foo<X> m() { return null; }
    
    private static void <warning descr="Private method 'foo(MyTestMethod.I1)' is never used">foo</warning>(I1 i) {System.out.println(i);}
    private static void <warning descr="Private method 'foo(MyTestMethod.I2)' is never used">foo</warning>(I2 i) {System.out.println(i);}
    private static void <warning descr="Private method 'foo(MyTestMethod.I3)' is never used">foo</warning>(I3 i) {System.out.println(i);}

    static {
       foo<error descr="Cannot resolve method 'foo(<method reference>)'">(MyTestMethod::m)</error>;
    }
}
