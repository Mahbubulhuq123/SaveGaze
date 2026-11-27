namespace SaveGaze.Core;

/// <summary>
/// Abstraction over a captured BGRA32 screen frame for zero-GDI-lock pixel analysis & unit testing.
/// </summary>
public interface ISkinImageSampler
{
    int Width { get; }
    int Height { get; }
    (byte R, byte G, byte B) GetRgb(int x, int y);
}

public sealed class BgraFrameSampler : ISkinImageSampler
{
    private readonly byte[] _bgra;
    private readonly int _stride;

    public int Width { get; }
    public int Height { get; }

    public BgraFrameSampler(byte[] bgra, int width, int height, int stride)
    {
        _bgra = bgra;
        Width = width;
        Height = height;
        _stride = stride;
    }

    public (byte R, byte G, byte B) GetRgb(int x, int y)
    {
        int offset = y * _stride + x * 4;
        return (_bgra[offset + 2], _bgra[offset + 1], _bgra[offset]);
    }
}

public static class SkinAnalyzer
{
    public static bool HasHumanSkin(
        this ISkinImageSampler frame,
        BoundingBox box,
        float minFraction = 0.08f,
        bool hasNearFace = false)
    {
        if (frame.Width < 4 || frame.Height < 4) return false;
        int left = Math.Clamp((int)box.X1, 0, frame.Width - 1);
        int top = Math.Clamp((int)box.Y1, 0, frame.Height - 1);
        int right = Math.Clamp((int)box.X2, left + 1, frame.Width);
        int bottom = Math.Clamp((int)box.Y2, top + 1, frame.Height);

        int regionW = right - left;
        int regionH = bottom - top;
        if (regionW < 4 || regionH < 4) return false;

        int stepX = Math.Max(1, regionW / 14);
        int stepY = Math.Max(1, regionH / 14);

        int skinCount = 0;
        int onionCount = 0;
        int totalSampled = 0;

        for (int y = top; y < bottom; y += stepY)
        {
            for (int x = left; x < right; x += stepX)
            {
                var (r, g, b) = frame.GetRgb(x, y);
                if (IsRedOnionPixel(r, g, b)) onionCount++;
                if (IsSkinPixel(r, g, b))
                {
                    skinCount++;
                }
                totalSampled++;
            }
        }

        if (totalSampled == 0) return false;
        if ((float)onionCount / totalSampled > 0.20f && !hasNearFace) return false;

        float skinFraction = (float)skinCount / totalSampled;
        float requiredFraction = hasNearFace ? 0.04f : minFraction;
        return skinFraction >= requiredFraction;
    }

    public static float GetSkinFraction(this ISkinImageSampler frame, BoundingBox box)
    {
        if (frame.Width < 4 || frame.Height < 4) return 0f;
        int left = Math.Clamp((int)box.X1, 0, frame.Width - 1);
        int top = Math.Clamp((int)box.Y1, 0, frame.Height - 1);
        int right = Math.Clamp((int)box.X2, left + 1, frame.Width);
        int bottom = Math.Clamp((int)box.Y2, top + 1, frame.Height);

        int regionW = right - left;
        int regionH = bottom - top;
        if (regionW < 4 || regionH < 4) return 0f;

        int stepX = Math.Max(1, regionW / 14);
        int stepY = Math.Max(1, regionH / 14);

        int skinCount = 0;
        int totalSampled = 0;

        for (int y = top; y < bottom; y += stepY)
        {
            for (int x = left; x < right; x += stepX)
            {
                var (r, g, b) = frame.GetRgb(x, y);
                if (IsSkinPixel(r, g, b))
                {
                    skinCount++;
                }
                totalSampled++;
            }
        }

        return totalSampled == 0 ? 0f : (float)skinCount / totalSampled;
    }

    private static bool IsRedOnionPixel(byte r, byte g, byte b) =>
        r is >= 110 and <= 220 && g is >= 20 and <= 85 && b is >= 20 and <= 95 && (r - g) > 65;

    private static bool IsSkinPixel(byte r, byte g, byte b)
    {
        if (IsRedOnionPixel(r, g, b)) return false;
        int maxC = Math.Max(r, Math.Max(g, b));
        int minC = Math.Min(r, Math.Min(g, b));
        int chroma = maxC - minC;
        if (chroma < 12) return false;

        int cb = (int)(-0.1687f * r - 0.3313f * g + 0.5000f * b + 128f);
        int cr = (int)(0.5000f * r - 0.4187f * g - 0.0813f * b + 128f);

        bool isWarmSkin = r > 32 && g > 20 && b > 14 &&
            chroma >= 14 &&
            r > g && r > b && (r - g) >= 6 &&
            cb is >= 74 and <= 132 &&
            cr is >= 131 and <= 178;

        bool isCoolSkin = r > 32 && g > 20 && b > 20 &&
            chroma >= 14 &&
            cb is >= 82 and <= 156 &&
            cr is >= 122 and <= 170 &&
            r >= g * 0.88f &&
            !(g > r + 8 && g > b + 8);

        return isWarmSkin || isCoolSkin;
    }

    public static float? ComputeMeanVisualLuma(this ISkinImageSampler frame)
    {
        if (frame.Width < 8 || frame.Height < 8) return null;
        Span<float> lumas = stackalloc float[256];
        int idx = 0;
        float sum = 0f;
        for (int r = 0; r < 16; r++)
        {
            int y = Math.Clamp(r * frame.Height / 16, 0, frame.Height - 1);
            for (int c = 0; c < 16; c++)
            {
                int x = Math.Clamp(c * frame.Width / 16, 0, frame.Width - 1);
                var (red, green, blue) = frame.GetRgb(x, y);
                float luma = 0.299f * red + 0.587f * green + 0.114f * blue;
                lumas[idx++] = luma;
                sum += luma;
            }
        }
        return MobileClipIntimateRules.HasVisualContent(lumas) ? sum / lumas.Length : null;
    }

    public static float FindMediaContainerBottom(
        this ISkinImageSampler frame,
        float minBottomY,
        BoundingBox anchorBox)
    {
        int bWidth = frame.Width;
        int bHeight = frame.Height;
        int checkYStart = Math.Clamp((int)(minBottomY + 12f), 0, Math.Max(0, bHeight - 1));
        if (checkYStart >= bHeight - 8) return bHeight;

        int sampleLeft = Math.Clamp((int)(anchorBox.CenterX - Math.Max(anchorBox.Width * 0.8f, 40f)), 0, Math.Max(0, bWidth - 1));
        int sampleRight = Math.Clamp((int)(anchorBox.CenterX + Math.Max(anchorBox.Width * 0.8f, 40f)), 0, Math.Max(0, bWidth - 1));
        if (sampleRight <= sampleLeft) return bHeight;
        int step = Math.Max(1, (sampleRight - sampleLeft) / 8);

        for (int y = checkYStart; y < bHeight - 4; y += 3)
        {
            int uiBgCount = 0;
            int total = 0;
            for (int x = sampleLeft; x <= sampleRight; x += step)
            {
                var (r, g, b) = frame.GetRgb(x, y);
                bool isDarkUi = r is >= 20 and <= 52 && g is >= 20 and <= 52 && b is >= 20 and <= 56 &&
                    Math.Abs(r - g) <= 5 && Math.Abs(g - b) <= 5;
                bool isLightUi = r >= 242 && g >= 242 && b >= 242;
                if (isDarkUi || isLightUi) uiBgCount++;
                total++;
            }
            if (total > 0 && uiBgCount >= (int)(total * 0.78f))
            {
                return y;
            }
        }
        return bHeight;
    }
}
