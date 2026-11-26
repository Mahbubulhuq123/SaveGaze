namespace SaveGaze.Core;

/// <summary>
/// YOLOv4-tiny person &amp; gender decoder matching `pordav4x3.onnx` (Class 0 = Male, Class 1 = Female).
/// </summary>
public static class GenderPersonDecoder
{
    public const int ModelWidth = 544;
    public const int ModelHeight = 320;
    private const int NumAnchorsPerHead = 3;
    private const int NumClasses = 2;
    private const int StridePerAnchor = 5 + NumClasses; // 7
    private const float ScaleXy = 1.05f;

    private static readonly (float W, float H)[] Anchors =
    [
        (10f, 14f),
        (23f, 27f),
        (37f, 58f),
        (81f, 82f),
        (135f, 169f),
        (344f, 319f)
    ];

    private static readonly int[][] HeadMasks =
    [
        [3, 4, 5], // Head 0: 10x17
        [0, 1, 2]  // Head 1: 20x34
    ];

    public static readonly string[] HeadNames = ["conv29_out", "conv36_out"];

    private static float Sigmoid(float x) => 1f / (1f + MathF.Exp(-x));

    public static List<PersonGenderBox> DecodeHead(
        float[][] headTensor,
        int gridH,
        int gridW,
        int headIndex,
        float scaleX,
        float scaleY,
        float offsetX,
        float offsetY,
        float maxWidth,
        float maxHeight,
        float threshold)
    {
        int[] mask = HeadMasks[headIndex];
        var candidates = new List<PersonGenderBox>();
        float offsetBias = 0.5f * (ScaleXy - 1.0f);

        for (int a = 0; a < NumAnchorsPerHead; a++)
        {
            int baseCh = a * StridePerAnchor;
            float anchorW = Anchors[mask[a]].W;
            float anchorH = Anchors[mask[a]].H;
            float[] txRow = headTensor[baseCh];
            float[] tyRow = headTensor[baseCh + 1];
            float[] twRow = headTensor[baseCh + 2];
            float[] thRow = headTensor[baseCh + 3];
            float[] objRow = headTensor[baseCh + 4];
            float[] cls0Row = headTensor[baseCh + 5];
            float[] cls1Row = headTensor[baseCh + 6];

            for (int cy = 0; cy < gridH; cy++)
            {
                int rowOffset = cy * gridW;
                for (int cx = 0; cx < gridW; cx++)
                {
                    int idx = rowOffset + cx;
                    float objScore = Sigmoid(objRow[idx]);
                    if (objScore < threshold) continue;

                    float maleScore = objScore * Sigmoid(cls0Row[idx]);
                    float femaleScore = objScore * Sigmoid(cls1Row[idx]);

                    float bestScore;
                    float otherScore;
                    PersonGender gender;
                    if (femaleScore >= maleScore)
                    {
                        bestScore = femaleScore;
                        otherScore = maleScore;
                        gender = PersonGender.Female;
                    }
                    else
                    {
                        bestScore = maleScore;
                        otherScore = femaleScore;
                        gender = PersonGender.Male;
                    }
                    if (bestScore < threshold) continue;

                    float bx = (cx + Sigmoid(txRow[idx]) * ScaleXy - offsetBias) / gridW;
                    float by = (cy + Sigmoid(tyRow[idx]) * ScaleXy - offsetBias) / gridH;
                    float bw = MathF.Exp(Math.Clamp(twRow[idx], -10f, 10f)) * anchorW / ModelWidth;
                    float bh = MathF.Exp(Math.Clamp(thRow[idx], -10f, 10f)) * anchorH / ModelHeight;

                    float cxPx = bx * ModelWidth * scaleX + offsetX;
                    float cyPx = by * ModelHeight * scaleY + offsetY;
                    float wPx = bw * ModelWidth * scaleX;
                    float hPx = bh * ModelHeight * scaleY;

                    var box = new BoundingBox(
                        X1: Math.Clamp(cxPx - wPx * 0.5f, 0f, maxWidth),
                        Y1: Math.Clamp(cyPx - hPx * 0.5f, 0f, maxHeight),
                        X2: Math.Clamp(cxPx + wPx * 0.5f, 0f, maxWidth),
                        Y2: Math.Clamp(cyPx + hPx * 0.5f, 0f, maxHeight)
                    );
                    if (box.Width >= 10f && box.Height >= 10f)
                    {
                        candidates.Add(new PersonGenderBox(gender, bestScore, box, otherScore));
                    }
                }
            }
        }

        return candidates;
    }

    public static List<PersonGenderBox> ApplyNms(IReadOnlyList<PersonGenderBox> detections, float iouThreshold = 0.45f)
    {
        if (detections.Count == 0) return [];
        var sorted = detections.OrderByDescending(d => d.Confidence).ToList();
        var kept = new List<PersonGenderBox>();

        foreach (var cand in sorted)
        {
            bool suppressed = kept.Any(existing =>
            {
                float iou = cand.Box.Iou(existing.Box);
                if (existing.Gender == cand.Gender)
                {
                    return iou > iouThreshold;
                }
                bool candInsideExisting =
                    cand.Box.CenterX >= existing.Box.X1 && cand.Box.CenterX <= existing.Box.X2 &&
                    cand.Box.CenterY >= existing.Box.Y1 && cand.Box.CenterY <= existing.Box.Y2 &&
                    existing.Confidence >= cand.Confidence * 1.25f;
                return iou > 0.28f || candInsideExisting;
            });
            if (!suppressed)
            {
                kept.Add(cand);
            }
        }

        return kept;
    }
}
