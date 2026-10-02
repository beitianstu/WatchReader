// 供桌面上跑分页测试用的最小 stub（只实现 Paginator/Book 实际调用的方法）。
// 放在独立目录，编译测试时优先于 android.jar，从而让分页纯逻辑能在真实 JDK 上运行。
package android.graphics;

/** 测量 stub：按固定字宽计算，行为确定，便于验证分页不变量 */
public class Paint {
    private float textSize = 13f;

    public void setTextSize(float size) { this.textSize = size; }
    public float getTextSize() { return textSize; }

    /** 每字符宽度：与字号成正比 */
    private float charWidth() { return textSize * 1.02f; }

    public float measureText(CharSequence text) {
        return text == null ? 0f : text.length() * charWidth();
    }

    public float measureText(String text) {
        return text == null ? 0f : text.length() * charWidth();
    }

    public float measureText(CharSequence text, int start, int end) {
        return Math.max(0, end - start) * charWidth();
    }

    public float measureText(String text, int start, int end) {
        return Math.max(0, end - start) * charWidth();
    }

    public int breakText(CharSequence text, int start, int end, boolean measureForwards,
                         float maxWidth, float[] measuredWidth) {
        float per = charWidth();
        int fit = per <= 0 ? (end - start) : (int) Math.floor(maxWidth / per);
        if (fit < 0) { fit = 0; }
        if (fit > end - start) { fit = end - start; }
        if (measuredWidth != null && measuredWidth.length > 0) {
            measuredWidth[0] = fit * per;
        }
        return fit;
    }

    /** 模拟真实字体度量 */
    public FontMetrics getFontMetrics() {
        FontMetrics fm = new FontMetrics();
        fm.top = -textSize * 0.9f;
        fm.ascent = -textSize * 0.85f;
        fm.descent = textSize * 0.2f;
        fm.bottom = textSize * 0.25f;
        return fm;
    }

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
    }
}
