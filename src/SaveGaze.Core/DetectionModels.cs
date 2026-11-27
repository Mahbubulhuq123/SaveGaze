namespace SaveGaze.Core;

public enum NudeLabel
{
    FemaleGenitaliaCovered = 0,
    FaceFemale = 1,
    ButtocksExposed = 2,
    FemaleBreastExposed = 3,
    FemaleGenitaliaExposed = 4,
    MaleBreastExposed = 5,
    AnusExposed = 6,
    FeetExposed = 7,
    BellyCovered = 8,
    FeetCovered = 9,
    ArmpitsCovered = 10,
    ArmpitsExposed = 11,
    FaceMale = 12,
    BellyExposed = 13,
    MaleGenitaliaExposed = 14,
    AnusCovered = 15,
    FemaleBreastCovered = 16,
    ButtocksCovered = 17
}

public static class NudeLabelExtensions
{
    public static NudeLabel? FromIndex(int index) =>
        index is >= 0 and <= 17 ? (NudeLabel)index : null;

    public static bool IsFace(this NudeLabel label) =>
        label is NudeLabel.FaceFemale or NudeLabel.FaceMale;

    public static bool? IsFemale(this NudeLabel label) => label switch
    {
        NudeLabel.FemaleGenitaliaCovered or NudeLabel.FaceFemale or NudeLabel.FemaleBreastExposed
            or NudeLabel.FemaleGenitaliaExposed or NudeLabel.FemaleBreastCovered => true,
        NudeLabel.MaleBreastExposed or NudeLabel.FaceMale or NudeLabel.MaleGenitaliaExposed => false,
        _ => null
    };

    public static bool IsExposed(this NudeLabel label) => label switch
    {
        NudeLabel.ButtocksExposed or NudeLabel.FemaleBreastExposed or NudeLabel.FemaleGenitaliaExposed
            or NudeLabel.MaleBreastExposed or NudeLabel.AnusExposed or NudeLabel.FeetExposed
            or NudeLabel.ArmpitsExposed or NudeLabel.BellyExposed or NudeLabel.MaleGenitaliaExposed => true,
        _ => false
    };
}

public enum PersonGender
{
    Male,
    Female
}

public enum GenderFilter
{
    MaleOnly,
    FemaleOnly,
    Both
}

public enum BlurStyle
{
    SolidBlack,
    DarkFrosted,
    LightCensor
}

public enum MediaAppContext
{
    ReelsShort,
    MovieSeriesPlayer,
    BrowserOrGeneral
}

public enum IntimateActionMode
{
    SmartContext,
    BackOnly
}

public enum DrmAppPolicy
{
    WarnOnly,
    AutoBlock,
    Off
}

public enum ExitActionType
{
    NextReelSwipe,
    SkipSceneForward,
    PauseAndBack
}

public readonly record struct BoundingBox(float X1, float Y1, float X2, float Y2)
{
    public float Width => X2 - X1;
    public float Height => Y2 - Y1;
    public float CenterX => (X1 + X2) * 0.5f;
    public float CenterY => (Y1 + Y2) * 0.5f;

    public float Iou(BoundingBox other)
    {
        float interX1 = Math.Max(X1, other.X1);
        float interY1 = Math.Max(Y1, other.Y1);
        float interX2 = Math.Min(X2, other.X2);
        float interY2 = Math.Min(Y2, other.Y2);

        if (interX2 <= interX1 || interY2 <= interY1) return 0f;

        float interArea = (interX2 - interX1) * (interY2 - interY1);
        float unionArea = (Width * Height) + (other.Width * other.Height) - interArea;
        return unionArea > 0f ? interArea / unionArea : 0f;
    }

    public BoundingBox Scale(float scaleX, float scaleY) =>
        new(X1 * scaleX, Y1 * scaleY, X2 * scaleX, Y2 * scaleY);
}

public sealed record PersonGenderBox(
    PersonGender Gender,
    float Confidence,
    BoundingBox Box,
    float OppositeConfidence = 0f
);

public sealed record Detection(
    NudeLabel Label,
    float Confidence,
    BoundingBox Box
);

public sealed record FilteredDetections(
    IReadOnlyList<Detection> ToBlur,
    IReadOnlyList<Detection> Faces,
    bool HasNsfwContent,
    IReadOnlyList<BoundingBox> MergedBlurBoxes,
    IReadOnlyList<BoundingBox> FaceBlurBoxes,
    IReadOnlyList<BoundingBox> ProtectedFaces
);

public sealed record SceneSafetyScores(float Nsfl, float Nsfw, float Sfw);

public sealed record ClipIntimateScore(
    float IntimateProb,
    float Margin,
    float MaxPosSim,
    float MaxNegSim
);

public sealed record IntimateSignals(
    bool Triggered,
    bool Instant,
    string Reason,
    float Confidence,
    bool IsKissOrBedCue
);

public sealed record AudioIntimateResult(
    float Score,
    string TopLabel,
    float Rms,
    bool DistractorDominant
);
