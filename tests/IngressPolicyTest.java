package dev.windowdeck.app;

public final class IngressPolicyTest {
 private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 public static void main(String[] args){
  check(IngressPolicy.canEnter(-1,-1,0),"first gesture must create without opening workbench");
  check(IngressPolicy.canEnter(-1,42,3),"stale full state after container removal must not hide entry");
  check(IngressPolicy.canEnter(42,42,1),"single window can accept another task");
  check(IngressPolicy.canEnter(42,42,2),"two windows can accept a third task");
  check(!IngressPolicy.canEnter(42,42,3),"full workbench must hide entry");
  check(!IngressPolicy.canEnter(42,41,1),"unpublished live container must not be replaced");
  check(!IngressPolicy.canEnter(42,42,0),"closing live container must not accept");
  System.out.println("PASS first-entry, stale-state, capacity and live-container identity cases");
 }
}
