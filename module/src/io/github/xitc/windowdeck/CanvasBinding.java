package io.github.xitc.windowdeck;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** ROM reflection handles resolved once during workbench startup. */
final class CanvasBinding {
 CanvasContract canvas;
 Class<?> viewApi;
 Class<?> managerApi;
 Object manager;
 Field interceptInput,rotateTaskLeash,cornerRadius,taskLeash,reparentAlign,superLocked,viewTransaction;
 Method resizeMethod,leashMatrix,leashMatrix4,leashRadius;
}
