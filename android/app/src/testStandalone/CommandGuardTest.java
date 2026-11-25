package com.veluvara.translatorlab;
public class CommandGuardTest {
    static void check(boolean condition) { if(!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        CommandGuard g=new CommandGuard();
        check(!g.accept("record_microphone","request-0001",100,100));
        check(!g.accept("self_test","request-0001",1,100));
        check(!g.accept("self_test","request-0001",111,100));
        check(!g.accept("self_test","bad/+",100,100));
        check(g.accept("self_test","request-0001",100,100));
        check(!g.accept("self_test","request-0001",100,115));
        check(!g.accept("self_test","request-0002",105,105));
        check(g.accept("self_test","request-0002",115,115));
        System.out.println("Command guard: 8 checks passed");
    }
}
