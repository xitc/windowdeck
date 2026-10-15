package io.github.xitc.windowdeck;

/** Installation identities; Java classes use the same namespace in both APKs. */
final class ModulePackages {
 static final String RESEARCH = "dev.windowdeck.app";
 static final String PUBLIC = "io.github.xitc.windowdeck";
 static boolean isModule(String packageName) {
  return RESEARCH.equals(packageName) || PUBLIC.equals(packageName);
 }
 private ModulePackages() {}
}
