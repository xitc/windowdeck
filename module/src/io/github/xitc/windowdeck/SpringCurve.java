package io.github.xitc.windowdeck;

/** The module's only motion curve: a closed-form COUI spring.
 *
 *  <p>This mirrors {@code com.coui.appcompat.animation.o} — the interpolator C17 itself uses
 *  for canvas geometry ({@code pscanvas/.../K.java:716} builds {@code new o(0.6d, 0.0d)}).
 *  The workbench therefore moves with the ROM's own numbers instead of an invented curve:
 *
 *  <pre>o(response, bounce)  →  ωn = 2π / response,  ζ = 1 - bounce</pre>
 *
 *  <p>Two properties are copied from COUI and both matter:
 *
 *  <ul>
 *   <li><b>Normalisation.</b> COUI returns {@code a(f) / a(1)} ({@code o.java:118-124}), so the
 *       curve lands <em>exactly</em> on its target. A raw spring settles at 1.017 and leaves the
 *       card a few dp short of its slot.</li>
 *   <li><b>Zero initial velocity.</b> {@code a'(0) = 0}. The cubic ease-out this replaces started
 *       at its maximum slope ({@code 3/T}) — that non-zero initial velocity is exactly what read
 *       as "瞬间启动" (see {@code 增量分析/动画对比评估.md} §9.4).</li>
 *  </ul>
 *
 *  <p>Deliberately pure Java: no androidx, no {@code android.*} types, so
 *  {@code tools/test_layout.sh} can compile and assert it.
 *
 *  <p>Retired curves are kept here for reference rather than deleted — see
 *  {@link #legacyEntrance(float)} (4.5 % overshoot) and {@link #legacySwitch(float)}.
 */
final class SpringCurve {
 /** C17's geometry family: {@code o(0.6, 0.0)} → ζ = 1.0, critically damped, zero overshoot.
  *  Used for large travel, where even 2 % overshoot is visible on a 280×560 dp card. */
 static final float GEOMETRY_RESPONSE = 0.6f;
 static final float GEOMETRY_BOUNCE = 0.0f;
 /** C17's transparent/preview family: {@code o(0.8, 0.22)} → ζ = 0.78, mild overshoot.
  *  Small elements only (preview chips, badges), same as {@code K.java:715}. */
 static final float PREVIEW_RESPONSE = 0.8f;
 static final float PREVIEW_BOUNCE = 0.22f;

 private static final double GEOMETRY_WN = omega(GEOMETRY_RESPONSE);
 private static final double GEOMETRY_ZETA = 1.0 - GEOMETRY_BOUNCE;
 private static final double GEOMETRY_UNIT = raw(GEOMETRY_WN, GEOMETRY_ZETA, 1.0);
 private static final double PREVIEW_WN = omega(PREVIEW_RESPONSE);
 private static final double PREVIEW_ZETA = 1.0 - PREVIEW_BOUNCE;
 private static final double PREVIEW_UNIT = raw(PREVIEW_WN, PREVIEW_ZETA, 1.0);

 /** Geometry family, normalised. */
 static float geometry(float fraction) {
  return sample(GEOMETRY_WN, GEOMETRY_ZETA, GEOMETRY_UNIT, fraction);
 }

 /** Preview family, normalised. */
 static float preview(float fraction) {
  return sample(PREVIEW_WN, PREVIEW_ZETA, PREVIEW_UNIT, fraction);
 }

 /** {@code o(response, bounce).getInterpolation(fraction)} — normalised, so {@code f=1 → 1}. */
 static float of(float response, float bounce, float fraction) {
  if (fraction <= 0f) return 0f;
  if (fraction >= 1f) return 1f;
  double wn = omega(response), zeta = 1.0 - bounce;
  return (float) (raw(wn, zeta, fraction) / raw(wn, zeta, 1.0));
 }

 private static float sample(double wn, double zeta, double unit, float fraction) {
  if (fraction <= 0f) return 0f;
  if (fraction >= 1f) return 1f;
  return (float) (raw(wn, zeta, fraction) / unit);
 }

 private static double omega(float response) {
  return 2.0 * Math.PI / Math.max(1e-4f, response);
 }

 /** The raw spring step response, before normalisation. Mirrors {@code o.java:56-79}. */
 private static double raw(double wn, double zeta, double t) {
  if (zeta < 1.0) {
   double wd = wn * Math.sqrt(1.0 - zeta * zeta);
   return 1.0 - Math.exp(-zeta * wn * t)
       * (Math.cos(wd * t) + (zeta * wn / wd) * Math.sin(wd * t));
  }
  if (zeta == 1.0) return 1.0 - (1.0 + wn * t) * Math.exp(-wn * t);
  double wd = wn * Math.sqrt(zeta * zeta - 1.0);
  return 1.0 - Math.exp(-zeta * wn * t)
      * (Math.cosh(wd * t) + (zeta * wn / wd) * Math.sinh(wd * t));
 }

 /** The retired entrance / shelf curve {@code 1 - e^(-5.5t)·cos(1.5πt)}.
  *
  *  <p>Kept so the defect stays reproducible: it overshoots by 4.55 % (peak 1.0455 at f=0.484)
  *  and spends the last half of the run crawling above 0.9. On a 280×560 dp card the 4.5 %
  *  overshoot is "先缩过头再弹回", and the crawl reads as "顿一下 + 卡着不动"
  *  ({@code docs/启动动画对比与改进.md} §1.2, 图 2-2). No production path uses it. */
 @Deprecated
 static float legacyEntrance(float t) {
  if (t <= 0f) return 0f;
  if (t >= 1f) return 1f;
  return 1f - (float) (Math.exp(-5.5 * t) * Math.cos(t * Math.PI * 1.5));
 }

 /** The retired switch curve: a self-made critically damped spring with ωn = 8, normalised so it
  *  lands on 1. Unrelated to any vivo or ROM parameter, which is why it was retired. */
 @Deprecated
 static float legacySwitch(float t) {
  if (t <= 0) return 0;
  if (t >= 1) return 1;
  return (float) ((1 - (1 + 8 * t) * Math.exp(-8 * t)) / (1 - 9 * Math.exp(-8)));
 }

 private SpringCurve() {}
}
