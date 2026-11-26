namespace SaveGaze.Core;

public static class NudeNetFilterEngine
{
    public const int ModelWidth = 320;
    public const int ModelHeight = 320;
    public const int NumClasses = 18;
    public const int NumPredictions = 2100;

    private enum ClusterGender
    {
        Female,
        Male,
        Unknown
    }

    private sealed class PersonCluster
    {
        public List<PersonGenderBox> PersonBoxes { get; } = [];
        public List<Detection> Faces { get; } = [];
        public List<Detection> IntimateDetections { get; } = [];

        public IEnumerable<BoundingBox> AllBoxes =>
            PersonBoxes.Select(p => p.Box)
                .Concat(Faces.Select(f => f.Box))
                .Concat(IntimateDetections.Select(d => d.Box));

        public float MinY => AllBoxes.Any() ? AllBoxes.Min(b => b.Y1) : 0f;
        public float MaxY => AllBoxes.Any() ? AllBoxes.Max(b => b.Y2) : 0f;
        public float Width => AllBoxes.Any() ? Math.Max(AllBoxes.Max(b => b.X2) - AllBoxes.Min(b => b.X1), 1f) : 1f;
        public float Height => AllBoxes.Any() ? Math.Max(MaxY - MinY, 1f) : 1f;
        public float CenterX => AllBoxes.Any() ? AllBoxes.Average(b => b.CenterX) : 0f;

        public PersonGenderBox? PrimaryPersonBox =>
            PersonBoxes.Count == 0 ? null : PersonBoxes.MaxBy(p => p.Confidence);

        public ClusterGender InferGender()
        {
            bool hasStrongFemaleGen = IntimateDetections.Any(d =>
                d.Label == NudeLabel.FemaleGenitaliaExposed && d.Confidence >= 0.42f);
            bool hasBareFemaleBreast = IntimateDetections.Any(d =>
                d.Label == NudeLabel.FemaleBreastExposed && d.Confidence >= 0.36f);
            bool hasMaleAnatomy = IntimateDetections.Any(d =>
                d.Label == NudeLabel.MaleGenitaliaExposed ||
                (d.Label == NudeLabel.MaleBreastExposed && d.Confidence >= 0.25f));
            bool hasFemaleFace = Faces.Any(f => f.Label == NudeLabel.FaceFemale);
            bool hasMaleFace = Faces.Any(f => f.Label == NudeLabel.FaceMale);

            if (hasMaleAnatomy && !hasStrongFemaleGen) return ClusterGender.Male;
            if (hasStrongFemaleGen && !hasMaleAnatomy) return ClusterGender.Female;

            var bestPerson = PrimaryPersonBox;
            if (bestPerson != null && bestPerson.Confidence >= 0.24f)
            {
                float margin = bestPerson.Confidence - bestPerson.OppositeConfidence;
                bool isCloseTie = bestPerson.OppositeConfidence > 0f && margin < 0.14f && bestPerson.Confidence < 0.48f;
                if (bestPerson.Gender == PersonGender.Male)
                {
                    bool hasStrongFemaleCovered = IntimateDetections.Any(d =>
                        d.Label is NudeLabel.FemaleBreastCovered or NudeLabel.FemaleGenitaliaCovered &&
                        d.Confidence >= 0.42f);
                    bool contradictedByFemale = hasBareFemaleBreast ||
                        (hasFemaleFace && !hasMaleFace && (isCloseTie || bestPerson.Confidence < 0.45f)) ||
                        (hasStrongFemaleCovered && !hasMaleFace && !hasMaleAnatomy && (isCloseTie || bestPerson.Confidence < 0.42f));
                    if (!contradictedByFemale) return ClusterGender.Male;
                }
                else
                {
                    if (hasMaleFace && !hasFemaleFace && (isCloseTie || bestPerson.Confidence < 0.42f))
                    {
                        return ClusterGender.Male;
                    }
                    if (!(bestPerson.OppositeConfidence > 0f && margin < 0.06f && bestPerson.Confidence < 0.45f))
                    {
                        return ClusterGender.Female;
                    }
                }
            }

            if (hasMaleFace && !hasFemaleFace) return ClusterGender.Male;
            if (hasFemaleFace && !hasMaleFace) return ClusterGender.Female;

            if (hasBareFemaleBreast && !hasMaleAnatomy && !hasMaleFace) return ClusterGender.Female;

            bool hasFemaleCovered = IntimateDetections.Any(d =>
                d.Label is NudeLabel.FemaleBreastCovered or NudeLabel.FemaleGenitaliaCovered);
            if (hasFemaleCovered && !hasMaleFace && !hasMaleAnatomy)
            {
                return Faces.Count == 0 && PersonBoxes.Count == 0
                    ? ClusterGender.Unknown
                    : ClusterGender.Female;
            }

            return ClusterGender.Unknown;
        }
    }

    private static readonly HashSet<NudeLabel> NsfwLabels =
    [
        NudeLabel.FemaleBreastExposed,
        NudeLabel.FemaleGenitaliaExposed,
        NudeLabel.MaleGenitaliaExposed,
        NudeLabel.MaleBreastExposed,
        NudeLabel.ButtocksExposed,
        NudeLabel.AnusExposed,
        NudeLabel.FemaleBreastCovered,
        NudeLabel.FemaleGenitaliaCovered,
        NudeLabel.ButtocksCovered,
        NudeLabel.AnusCovered,
        NudeLabel.BellyExposed,
        NudeLabel.ArmpitsExposed,
        NudeLabel.FeetExposed
    ];

    public static List<Detection> PostProcess(
        float[][] output,
        float tileWidth,
        float tileHeight,
        float offsetX = 0f,
        float offsetY = 0f,
        float confidenceThreshold = 0.25f)
    {
        float scaleX = tileWidth / ModelWidth;
        float scaleY = tileHeight / ModelHeight;
        var candidates = new List<Detection>();

        for (int i = 0; i < NumPredictions; i++)
        {
            float cx = output[0][i];
            float cy = output[1][i];
            float w = output[2][i];
            float h = output[3][i];

            int bestClassIdx = 0;
            float bestClassScore = output[4][i];
            for (int c = 1; c < NumClasses; c++)
            {
                float score = output[4 + c][i];
                if (score > bestClassScore)
                {
                    bestClassScore = score;
                    bestClassIdx = c;
                }
            }

            float femaleFaceScore = output[4 + (int)NudeLabel.FaceFemale][i];
            float maleFaceScore = output[4 + (int)NudeLabel.FaceMale][i];
            if (bestClassIdx == (int)NudeLabel.FaceFemale &&
                (maleFaceScore >= femaleFaceScore * 0.55f || maleFaceScore >= 0.14f))
            {
                bestClassIdx = (int)NudeLabel.FaceMale;
                bestClassScore = Math.Max(maleFaceScore, bestClassScore);
            }

            var labelOpt = NudeLabelExtensions.FromIndex(bestClassIdx);
            if (labelOpt is null) continue;
            var label = labelOpt.Value;

            float minThreshold = label switch
            {
                NudeLabel.FaceFemale => 0.18f,
                NudeLabel.FaceMale => 0.16f,
                NudeLabel.FemaleBreastExposed or NudeLabel.FemaleGenitaliaExposed or
                NudeLabel.ButtocksExposed or NudeLabel.AnusExposed => Math.Min(confidenceThreshold, 0.14f),
                NudeLabel.FeetExposed => Math.Min(confidenceThreshold, 0.16f),
                NudeLabel.ArmpitsExposed or NudeLabel.BellyExposed => Math.Min(confidenceThreshold, 0.15f),
                NudeLabel.MaleBreastExposed or NudeLabel.MaleGenitaliaExposed => Math.Min(confidenceThreshold, 0.16f),
                NudeLabel.FemaleGenitaliaCovered or NudeLabel.ButtocksCovered => Math.Min(confidenceThreshold, 0.20f),
                NudeLabel.FemaleBreastCovered => Math.Min(confidenceThreshold, 0.22f),
                _ => confidenceThreshold
            };
            if (bestClassScore < minThreshold) continue;

            var (padW, padH) = GetLabelPadding(label, w, h);
            var box = new BoundingBox(
                X1: Math.Max(0f, (cx - w * 0.5f - padW) * scaleX + offsetX),
                Y1: Math.Max(0f, (cy - h * 0.5f - padH) * scaleY + offsetY),
                X2: Math.Max(0f, (cx + w * 0.5f + padW) * scaleX + offsetX),
                Y2: Math.Max(0f, (cy + h * 0.5f + padH) * scaleY + offsetY)
            );
            if (box.Width > 4f && box.Height > 4f)
            {
                candidates.Add(new Detection(label, bestClassScore, box));
            }
        }

        return candidates;
    }

    public static List<Detection> Nms(IReadOnlyList<Detection> detections, float iouThreshold = 0.45f)
    {
        if (detections.Count == 0) return [];
        var kept = new List<Detection>();
        foreach (var group in detections.GroupBy(d => d.Label))
        {
            var sorted = group.OrderByDescending(d => d.Confidence).ToList();
            while (sorted.Count > 0)
            {
                var best = sorted[0];
                sorted.RemoveAt(0);
                kept.Add(best);
                sorted.RemoveAll(other => best.Box.Iou(other.Box) > iouThreshold);
            }
        }
        return kept;
    }

    private static (float PadW, float PadH) GetLabelPadding(NudeLabel label, float w, float h) => label switch
    {
        NudeLabel.FemaleBreastCovered or NudeLabel.FemaleBreastExposed => (w * 0.25f, h * 0.30f),
        NudeLabel.FemaleGenitaliaCovered or NudeLabel.FemaleGenitaliaExposed => (w * 0.20f, h * 0.22f),
        NudeLabel.BellyExposed => (w * 0.35f, h * 0.35f),
        NudeLabel.ButtocksCovered or NudeLabel.ButtocksExposed => (w * 0.22f, h * 0.25f),
        NudeLabel.ArmpitsExposed => (w * 0.65f, h * 0.60f),
        NudeLabel.FeetExposed => (w * 0.35f, h * 0.70f),
        NudeLabel.MaleBreastExposed or NudeLabel.MaleGenitaliaExposed => (w * 0.10f, h * 0.12f),
        NudeLabel.AnusCovered or NudeLabel.AnusExposed => (w * 0.10f, h * 0.10f),
        NudeLabel.FaceFemale or NudeLabel.FaceMale => (0f, 0f),
        _ => (w * 0.10f, h * 0.10f)
    };

    private static BoundingBox PadFaceBox(BoundingBox box)
    {
        float w = box.Width;
        float h = box.Height;
        return new BoundingBox(
            Math.Max(0f, box.X1 - w * 0.18f),
            Math.Max(0f, box.Y1 - h * 0.24f),
            box.X2 + w * 0.18f,
            box.Y2 + h * 0.14f
        );
    }

    private static BoundingBox EstimateHeadBoxFromPerson(BoundingBox person)
    {
        float pw = person.Width;
        float ph = person.Height;
        float insetX = pw * 0.10f;
        float headBottom = ph <= pw * 1.45f
            ? person.Y1 + ph * 0.72f
            : person.Y1 + Math.Min(ph * 0.36f, pw * 0.90f);
        return new BoundingBox(
            Math.Max(0f, person.X1 + insetX),
            Math.Max(0f, person.Y1 - ph * 0.04f),
            Math.Max(person.X1 + 8f, person.X2 - insetX),
            Math.Max(person.Y1 + 12f, headBottom)
        );
    }

    public static FilteredDetections FilterDetectionsPure(
        IReadOnlyList<Detection> detections,
        IReadOnlyList<PersonGenderBox>? personDetections = null,
        GenderFilter genderFilter = GenderFilter.Both,
        bool blurFaces = false,
        ISkinImageSampler? frame = null)
    {
        personDetections ??= [];
        var allFaces = detections.Where(d => d.Label.IsFace()).ToList();

        var rawNsfwParts = detections.Where(det =>
            NsfwLabels.Contains(det.Label) &&
            allFaces.All(face => det.Box.Iou(face.Box) <= 0.35f)).ToList();

        var validNsfwParts = frame != null
            ? rawNsfwParts.Where(det => det.Label switch
            {
                NudeLabel.FemaleBreastCovered => det.Confidence >= 0.34f || frame.GetSkinFraction(det.Box) >= 0.10f,
                NudeLabel.MaleBreastExposed => frame.GetSkinFraction(det.Box) >= 0.22f,
                _ => true
            }).ToList()
            : rawNsfwParts;

        // 1. Seed PersonClusters with fast PordaV4x3 person boxes
        var clusters = new List<PersonCluster>();
        foreach (var person in personDetections.OrderByDescending(p => p.Confidence))
        {
            var overlapping = clusters.FirstOrDefault(c => c.PersonBoxes.Any(pb =>
            {
                float minW = Math.Min(person.Box.Width, pb.Box.Width);
                bool closeCenter = MathF.Abs(person.Box.CenterX - pb.Box.CenterX) <= minW * 0.40f;
                bool inside = person.Box.CenterX >= pb.Box.X1 && person.Box.CenterX <= pb.Box.X2 &&
                    person.Box.CenterY >= pb.Box.Y1 && person.Box.CenterY <= pb.Box.Y2 &&
                    pb.Confidence >= person.Confidence * 1.20f && closeCenter;
                return person.Box.Iou(pb.Box) > 0.35f || inside;
            }));
            if (overlapping != null)
            {
                overlapping.PersonBoxes.Add(person);
            }
            else
            {
                var nc = new PersonCluster();
                nc.PersonBoxes.Add(person);
                clusters.Add(nc);
            }
        }

        // 2. Attach NudeNet faces
        foreach (var face in allFaces)
        {
            var matching = clusters.FirstOrDefault(c => c.PersonBoxes.Any(pb =>
            {
                bool insideX = face.Box.CenterX >= pb.Box.X1 - pb.Box.Width * 0.15f &&
                               face.Box.CenterX <= pb.Box.X2 + pb.Box.Width * 0.15f;
                bool insideTop = face.Box.CenterY >= pb.Box.Y1 - pb.Box.Height * 0.15f &&
                                 face.Box.CenterY <= pb.Box.Y2;
                return face.Box.Iou(pb.Box) > 0.05f || (insideX && insideTop);
            }));
            if (matching != null)
            {
                matching.Faces.Add(face);
            }
            else
            {
                var nc = new PersonCluster();
                nc.Faces.Add(face);
                clusters.Add(nc);
            }
        }

        // 3. Assign intimate body parts to nearest matching cluster
        foreach (var det in validNsfwParts)
        {
            var box = det.Box;
            PersonCluster? bestCluster = null;
            float bestScore = float.MaxValue;

            foreach (var c in clusters)
            {
                bool isStrictMaleDet = det.Label is NudeLabel.MaleBreastExposed or NudeLabel.MaleGenitaliaExposed;
                bool isStrictFemaleDet = det.Label == NudeLabel.FemaleGenitaliaExposed && det.Confidence >= 0.42f;
                bool clusterIsConfirmedFemale =
                    (c.PrimaryPersonBox is { Gender: PersonGender.Female, Confidence: >= 0.50f }) ||
                    c.Faces.Any(f => f.Label == NudeLabel.FaceFemale);
                bool clusterIsConfirmedMale =
                    (c.PrimaryPersonBox is { Gender: PersonGender.Male, Confidence: >= 0.50f }) ||
                    c.Faces.Any(f => f.Label == NudeLabel.FaceMale);

                if (isStrictMaleDet && clusterIsConfirmedFemale) continue;
                if (isStrictFemaleDet && clusterIsConfirmedMale) continue;

                bool insidePerson = c.PersonBoxes.Any(pb =>
                    box.Iou(pb.Box) > 0.05f ||
                    (box.CenterX >= pb.Box.X1 - pb.Box.Width * 0.15f &&
                     box.CenterX <= pb.Box.X2 + pb.Box.Width * 0.15f &&
                     box.CenterY >= pb.Box.Y1 - 16f &&
                     box.CenterY <= pb.Box.Y2 + Math.Min(pb.Box.Height * 0.15f, 40f)));

                float clusterCenterY = (c.MinY + c.MaxY) * 0.5f;
                float dist2d = MathF.Sqrt(
                    MathF.Pow(box.CenterX - c.CenterX, 2f) +
                    MathF.Pow((box.CenterY - clusterCenterY) * 1.35f, 2f));

                if (insidePerson)
                {
                    if (dist2d < bestScore)
                    {
                        bestScore = dist2d;
                        bestCluster = c;
                    }
                    continue;
                }

                if (c.PersonBoxes.Count == 0)
                {
                    if (c.Faces.Count > 0)
                    {
                        float faceW = c.Faces.Max(f => f.Box.Width);
                        float faceBottom = c.Faces.Max(f => f.Box.Y2);
                        float faceTop = c.Faces.Min(f => f.Box.Y1);
                        float faceH = c.Faces.Max(f => f.Box.Height);

                        float vertDist = box.Y1 - faceBottom;
                        bool isBelowFace = box.Y1 >= faceTop - 20f;
                        bool isWithinTorsoReach = vertDist <= faceH * 4.5f;
                        float allowHorizDist = Math.Max(faceW * 1.6f, box.Width * 1.2f);
                        float dx = MathF.Abs(box.CenterX - c.CenterX);

                        if (isBelowFace && isWithinTorsoReach && dx < allowHorizDist && dist2d < bestScore)
                        {
                            bestScore = dist2d;
                            bestCluster = c;
                        }
                    }
                    else if (c.IntimateDetections.Count > 0)
                    {
                        float vertGap = Math.Max(Math.Max(box.Y1 - c.MaxY, c.MinY - box.Y2), 0f);
                        float maxReachY = Math.Max(c.Height, box.Height) * 0.9f;
                        float allowHorizDist = Math.Max(c.Width, box.Width) * 0.95f;
                        float dx = MathF.Abs(box.CenterX - c.CenterX);
                        if (vertGap <= maxReachY && dx <= allowHorizDist && dist2d < bestScore)
                        {
                            bestScore = dist2d;
                            bestCluster = c;
                        }
                    }
                }
            }

            if (bestCluster == null)
            {
                bestCluster = new PersonCluster();
                clusters.Add(bestCluster);
            }
            bestCluster.IntimateDetections.Add(det);
        }

        // Compute resolved gender & correct face labels
        var clusterGenderMap = clusters.ToDictionary(c => c, c => c.InferGender());
        var faceCorrectionMap = new Dictionary<Detection, Detection>();

        foreach (var c in clusters)
        {
            var g = clusterGenderMap[c];
            var updated = new List<Detection>(c.Faces.Count);
            foreach (var f in c.Faces)
            {
                var corrected = g switch
                {
                    ClusterGender.Male when f.Label != NudeLabel.FaceMale => f with { Label = NudeLabel.FaceMale },
                    ClusterGender.Female when f.Label != NudeLabel.FaceFemale => f with { Label = NudeLabel.FaceFemale },
                    _ => f
                };
                faceCorrectionMap[f] = corrected;
                updated.Add(corrected);
            }
            c.Faces.Clear();
            c.Faces.AddRange(updated);
        }

        var correctedFaces = allFaces.Select(f => faceCorrectionMap.GetValueOrDefault(f, f)).ToList();
        var updatedMaleFaces = correctedFaces.Where(f => f.Label == NudeLabel.FaceMale).ToList();
        var updatedFemaleFaces = correctedFaces.Where(f => f.Label == NudeLabel.FaceFemale).ToList();

        // 4. Face blur boxes when blurFaces == true
        var faceBlurBoxes = new List<BoundingBox>();
        if (blurFaces)
        {
            foreach (var c in clusters)
            {
                var g = clusterGenderMap[c];
                bool matchesFilter = genderFilter switch
                {
                    GenderFilter.Both => g is ClusterGender.Female or ClusterGender.Male,
                    GenderFilter.FemaleOnly => g == ClusterGender.Female,
                    GenderFilter.MaleOnly => g == ClusterGender.Male,
                    _ => false
                };
                if (!matchesFilter) continue;

                if (c.Faces.Count > 0)
                {
                    foreach (var f in c.Faces) faceBlurBoxes.Add(PadFaceBox(f.Box));
                }
                else if (c.PrimaryPersonBox is { Confidence: >= 0.34f } pb)
                {
                    faceBlurBoxes.Add(EstimateHeadBoxFromPerson(pb.Box));
                }
            }
        }

        bool sceneHasFemaleFace = updatedFemaleFaces.Count > 0;
        bool sceneHasMaleFace = updatedMaleFaces.Count > 0;

        // 5. Filter semi-nude / nude clusters matching target gender
        var keptBodyClusters = clusters.Where(c =>
        {
            if (c.IntimateDetections.Count == 0) return false;

            bool hasSemiOrNudeCue = c.IntimateDetections.Any(d =>
                d.Label is NudeLabel.FemaleBreastExposed or NudeLabel.FemaleGenitaliaExposed or
                NudeLabel.MaleGenitaliaExposed or NudeLabel.MaleBreastExposed or
                NudeLabel.ButtocksExposed or NudeLabel.AnusExposed or
                NudeLabel.FemaleGenitaliaCovered or NudeLabel.ButtocksCovered or
                NudeLabel.AnusCovered or NudeLabel.BellyExposed ||
                (d.Label == NudeLabel.ArmpitsExposed && d.Confidence >= 0.28f) ||
                (d.Label == NudeLabel.FemaleBreastCovered && (d.Confidence >= 0.28f || frame != null)));
            if (!hasSemiOrNudeCue) return false;

            var g = clusterGenderMap[c];
            bool hasMaleFace = c.Faces.Any(f => f.Label == NudeLabel.FaceMale);
            bool hasFemaleFace = c.Faces.Any(f => f.Label == NudeLabel.FaceFemale);

            switch (genderFilter)
            {
                case GenderFilter.Both:
                    if (g == ClusterGender.Male || (hasMaleFace && !hasFemaleFace))
                    {
                        return c.IntimateDetections.Any(d =>
                            d.Label is NudeLabel.MaleBreastExposed or NudeLabel.BellyExposed or
                            NudeLabel.ArmpitsExposed or NudeLabel.ButtocksCovered or
                            NudeLabel.ButtocksExposed or NudeLabel.MaleGenitaliaExposed);
                    }
                    return true;

                case GenderFilter.FemaleOnly:
                    if (g == ClusterGender.Male || (hasMaleFace && !hasFemaleFace)) return false;
                    if (g == ClusterGender.Female || hasFemaleFace)
                    {
                        return c.IntimateDetections.Any(d =>
                            d.Label != NudeLabel.MaleBreastExposed &&
                            d.Label != NudeLabel.MaleGenitaliaExposed);
                    }

                    bool hasFemaleAnatomy = c.IntimateDetections.Any(d =>
                        d.Label is NudeLabel.FemaleBreastExposed or NudeLabel.FemaleGenitaliaExposed ||
                        (d.Label == NudeLabel.FemaleBreastCovered && d.Confidence >= 0.30f) ||
                        (d.Label == NudeLabel.FemaleGenitaliaCovered && d.Confidence >= 0.28f));
                    bool hasStrongExplicitFemale = c.IntimateDetections.Any(d =>
                        (d.Label == NudeLabel.FemaleBreastExposed && d.Confidence >= 0.34f) ||
                        (d.Label == NudeLabel.FemaleGenitaliaExposed && d.Confidence >= 0.30f));
                    bool hasMaleAnat = c.IntimateDetections.Any(d =>
                        d.Label is NudeLabel.MaleBreastExposed or NudeLabel.MaleGenitaliaExposed);
                    if (hasMaleAnat) return false;
                    if (sceneHasMaleFace && !sceneHasFemaleFace && !hasFemaleAnatomy) return false;

                    int semiCueCount = c.IntimateDetections.Count(d =>
                        d.Label is NudeLabel.BellyExposed or NudeLabel.ArmpitsExposed or
                        NudeLabel.FemaleBreastCovered or NudeLabel.FemaleGenitaliaCovered or
                        NudeLabel.ButtocksExposed or NudeLabel.ButtocksCovered);

                    return hasStrongExplicitFemale || hasFemaleAnatomy ||
                        c.IntimateDetections.Any(d =>
                            (d.Label == NudeLabel.ButtocksExposed && d.Confidence >= 0.32f) ||
                            (d.Label == NudeLabel.ButtocksCovered && d.Confidence >= 0.38f)) ||
                        (semiCueCount >= 2 && c.IntimateDetections.Any(d =>
                            d.Label is NudeLabel.FemaleBreastCovered or NudeLabel.ButtocksCovered or NudeLabel.ButtocksExposed));

                case GenderFilter.MaleOnly:
                    if (g == ClusterGender.Female) return false;
                    if (hasFemaleFace && !hasMaleFace && g != ClusterGender.Male) return false;
                    bool maleCue = c.IntimateDetections.Any(d =>
                        d.Label is NudeLabel.MaleBreastExposed or NudeLabel.BellyExposed or
                        NudeLabel.ArmpitsExposed or NudeLabel.ButtocksCovered or
                        NudeLabel.ButtocksExposed or NudeLabel.MaleGenitaliaExposed);
                    bool maleAnat = c.IntimateDetections.Any(d =>
                        d.Label is NudeLabel.MaleBreastExposed or NudeLabel.MaleGenitaliaExposed);
                    return maleCue && (g == ClusterGender.Male || hasMaleFace || maleAnat || !hasFemaleFace);

                default:
                    return true;
            }
        }).ToList();

        var keptBodyFaces = keptBodyClusters.SelectMany(c => c.Faces).ToHashSet();
        List<BoundingBox> protectedFaces = !blurFaces
            ? correctedFaces.Select(f =>
            {
                if (keptBodyFaces.Contains(f))
                {
                    float jawBottom = Math.Max(f.Box.Y2 - f.Box.Height * 0.14f, f.Box.Y1 + f.Box.Height * 0.72f);
                    return new BoundingBox(f.Box.X1, f.Box.Y1, f.Box.X2, jawBottom);
                }
                return f.Box;
            }).ToList()
            : genderFilter switch
            {
                GenderFilter.FemaleOnly => updatedMaleFaces.Select(f => f.Box).ToList(),
                GenderFilter.MaleOnly => updatedFemaleFaces.Select(f => f.Box).ToList(),
                _ => []
            };

        var oppositeGenderClusters = genderFilter switch
        {
            GenderFilter.FemaleOnly => clusters.Where(c => clusterGenderMap[c] == ClusterGender.Male).ToList(),
            GenderFilter.MaleOnly => clusters.Where(c => clusterGenderMap[c] == ClusterGender.Female).ToList(),
            _ => []
        };

        var filteredParts = keptBodyClusters
            .SelectMany(c => c.IntimateDetections)
            .Where(det => !IsUnderOppositeFace(det.Box, genderFilter, updatedFemaleFaces, updatedMaleFaces))
            .ToHashSet();

        // 6. Whole-body blur boxes
        var bodyBoxes = new List<BoundingBox>();
        if (filteredParts.Count > 0)
        {
            foreach (var c in keptBodyClusters)
            {
                var partsInCluster = c.IntimateDetections.Where(filteredParts.Contains).ToList();
                if (partsInCluster.Count == 0) continue;

                var pBox = c.PrimaryPersonBox?.Box;
                float partMinX = partsInCluster.Min(d => d.Box.X1);
                float partMaxX = partsInCluster.Max(d => d.Box.X2);
                float partMinY = partsInCluster.Min(d => d.Box.Y1);
                float partMaxY = partsInCluster.Max(d => d.Box.Y2);
                var clusterBounds = new BoundingBox(
                    pBox is { } pb1 ? Math.Min(partMinX, pb1.X1) : partMinX,
                    pBox is { } pb2 ? Math.Min(partMinY, pb2.Y1) : partMinY,
                    pBox is { } pb3 ? Math.Max(partMaxX, pb3.X2) : partMaxX,
                    pBox is { } pb4 ? Math.Max(partMaxY, pb4.Y2) : partMaxY
                );

                var face = c.Faces.Count > 0 ? c.Faces.MinBy(f => f.Box.Y1) : null;
                BoundingBox rawBox;
                if (face != null)
                {
                    float faceW = face.Box.Width;
                    float faceH = face.Box.Height;
                    float personCenterX = pBox is { } pb
                        ? pb.CenterX
                        : (face.Box.CenterX + (partMinX + partMaxX) * 0.5f) * 0.5f;
                    float halfW = Math.Max(faceW * 1.50f,
                        Math.Max((pBox?.Width ?? clusterBounds.Width) * 0.54f, (partMaxX - partMinX) * 0.62f));
                    float left = Math.Max(0f, Math.Min(Math.Max(0f, personCenterX - halfW), partMinX - 10f));
                    float right = Math.Max(personCenterX + halfW, partMaxX + 10f);

                    float topY = blurFaces
                        ? Math.Min(PadFaceBox(face.Box).Y1, pBox?.Y1 ?? face.Box.Y1)
                        : Math.Max(0f, Math.Max(face.Box.Y2 - faceH * 0.14f, face.Box.Y1 + faceH * 0.72f));

                    bool hasLower = partsInCluster.Any(d =>
                        d.Label == NudeLabel.FeetExposed || d.Box.Y2 > face.Box.Y2 + faceH * 3.8f);
                    float naturalBottomY = Math.Max(
                        partMaxY + 25f,
                        Math.Max(pBox?.Y2 ?? 0f, face.Box.Y2 + faceH * (hasLower ? 6.2f : 3.4f)));
                    float containerBottom = frame?.FindMediaContainerBottom(partMaxY, clusterBounds) ?? float.MaxValue;
                    float bottomY = Math.Max(partMaxY + 15f, Math.Min(naturalBottomY, containerBottom));
                    rawBox = new BoundingBox(left, topY, right, bottomY);
                }
                else if (pBox is { } pb)
                {
                    float padX = Math.Max(pb.Width * 0.06f, 12f);
                    float left = Math.Max(0f, Math.Min(pb.X1 - padX, partMinX - 12f));
                    float right = Math.Max(pb.X2 + padX, partMaxX + 12f);
                    float topY = blurFaces
                        ? Math.Max(0f, pb.Y1 - pb.Height * 0.05f)
                        : Math.Max(0f, Math.Min(pb.Y1, partMinY - 12f));
                    float naturalBottomY = Math.Max(pb.Y2 + 12f, partMaxY + 24f);
                    float containerBottom = frame?.FindMediaContainerBottom(partMaxY, clusterBounds) ?? float.MaxValue;
                    float bottomY = Math.Max(partMaxY + 15f, Math.Min(naturalBottomY, containerBottom));
                    rawBox = new BoundingBox(left, topY, right, bottomY);
                }
                else
                {
                    float partW = Math.Max(partMaxX - partMinX, 1f);
                    float partH = Math.Max(partMaxY - partMinY, 1f);
                    var topPart = partsInCluster.MinBy(d => d.Box.Y1)!;
                    float topPartH = Math.Max(topPart.Box.Height, 1f);
                    float padX = Math.Max(partW * 0.32f, 24f);
                    float left = Math.Max(0f, partMinX - padX);
                    float right = partMaxX + padX;
                    bool hasBacksideOnly = partsInCluster.Any(d =>
                        d.Label is NudeLabel.ButtocksExposed or NudeLabel.ButtocksCovered or NudeLabel.AnusExposed);
                    bool hasUpperChest = partsInCluster.Any(d =>
                        d.Label is NudeLabel.FemaleBreastCovered or NudeLabel.FemaleBreastExposed or
                        NudeLabel.MaleBreastExposed or NudeLabel.ArmpitsExposed);
                    float topY = hasBacksideOnly
                        ? Math.Max(0f, partMinY - topPartH * 2.2f)
                        : blurFaces && hasUpperChest
                            ? Math.Max(0f, partMinY - topPartH * 1.45f)
                            : hasUpperChest
                                ? Math.Max(0f, partMinY - topPartH * 0.78f)
                                : Math.Max(0f, partMinY - topPartH * 1.25f);
                    float naturalBottomY = partMaxY + partH * 0.45f;
                    float containerBottom = frame?.FindMediaContainerBottom(partMaxY, clusterBounds) ?? float.MaxValue;
                    float bottomY = Math.Max(partMaxY + 15f, Math.Min(naturalBottomY, containerBottom));
                    rawBox = new BoundingBox(left, topY, right, bottomY);
                }

                var clamped = ClampAwayFromOppositeGender(
                    rawBox,
                    clusterBounds.CenterX,
                    partMinX,
                    partMaxX,
                    oppositeGenderClusters,
                    genderFilter == GenderFilter.FemaleOnly ? updatedMaleFaces : updatedFemaleFaces);

                if (clamped.Width > 8f && clamped.Height > 8f)
                {
                    bodyBoxes.Add(clamped);
                }
            }
        }

        var allCandidateBlurBoxes = MergeAdjacentBoxes(bodyBoxes.Concat(faceBlurBoxes).ToList());
        return new FilteredDetections(
            ToBlur: filteredParts.ToList(),
            Faces: correctedFaces,
            HasNsfwContent: allCandidateBlurBoxes.Count > 0,
            MergedBlurBoxes: allCandidateBlurBoxes,
            FaceBlurBoxes: faceBlurBoxes,
            ProtectedFaces: protectedFaces);
    }

    private static BoundingBox ClampAwayFromOppositeGender(
        BoundingBox box,
        float subjectCenterX,
        float protectedMinX,
        float protectedMaxX,
        List<PersonCluster> oppositeClusters,
        List<Detection> oppositeFaces)
    {
        if (oppositeClusters.Count == 0 && oppositeFaces.Count == 0) return box;
        float x1 = box.X1;
        float x2 = box.X2;
        var oppCenters = oppositeClusters
            .Where(oc => oc.MaxY >= box.Y1 && oc.MinY <= box.Y2 && MathF.Abs(oc.CenterX - subjectCenterX) > 25f)
            .Select(oc => oc.CenterX)
            .Concat(oppositeFaces
                .Where(of => of.Box.Y2 + of.Box.Height * 5f >= box.Y1 && of.Box.Y1 <= box.Y2 &&
                             MathF.Abs(of.Box.CenterX - subjectCenterX) > 25f)
                .Select(of => of.Box.CenterX));

        foreach (float oppCx in oppCenters)
        {
            float midpoint = (subjectCenterX + oppCx) * 0.5f;
            if (oppCx < subjectCenterX && midpoint < protectedMinX)
            {
                x1 = Math.Max(x1, midpoint + 2f);
            }
            else if (oppCx > subjectCenterX && midpoint > protectedMaxX)
            {
                x2 = Math.Min(x2, midpoint - 2f);
            }
        }
        return new BoundingBox(x1, box.Y1, Math.Max(x2, x1 + 8f), box.Y2);
    }

    private static bool IsUnderOppositeFace(
        BoundingBox box,
        GenderFilter genderFilter,
        List<Detection> femaleFaces,
        List<Detection> maleFaces)
    {
        if (genderFilter == GenderFilter.Both) return false;
        var (oppFaces, sameFaces) = genderFilter == GenderFilter.FemaleOnly
            ? (maleFaces, femaleFaces)
            : (femaleFaces, maleFaces);

        return oppFaces.Any(opp =>
        {
            float bodyTop = opp.Box.Y1 - 10f;
            float bodyBottom = opp.Box.Y2 + opp.Box.Height * 5.0f;
            float colLeft = opp.Box.CenterX - opp.Box.Width * 1.2f;
            float colRight = opp.Box.CenterX + opp.Box.Width * 1.2f;
            bool closerSame = sameFaces.Any(same =>
                MathF.Sqrt(MathF.Pow(same.Box.CenterX - box.CenterX, 2f) + MathF.Pow(same.Box.Y2 - box.Y1, 2f)) <
                MathF.Sqrt(MathF.Pow(opp.Box.CenterX - box.CenterX, 2f) + MathF.Pow(opp.Box.Y2 - box.Y1, 2f)));
            return !closerSame &&
                box.CenterY >= bodyTop && box.CenterY <= bodyBottom &&
                box.CenterX >= colLeft && box.CenterX <= colRight;
        });
    }

    public static List<BoundingBox> MergeAdjacentBoxes(List<BoundingBox> boxes)
    {
        if (boxes.Count <= 1) return boxes;
        var remaining = new List<BoundingBox>(boxes);
        var merged = new List<BoundingBox>();

        while (remaining.Count > 0)
        {
            var curr = remaining[0];
            remaining.RemoveAt(0);
            bool changed = true;
            while (changed)
            {
                changed = false;
                int i = 0;
                while (i < remaining.Count)
                {
                    var cand = remaining[i];
                    float ox = Math.Min(curr.X2, cand.X2) - Math.Max(curr.X1, cand.X1);
                    float oy = Math.Min(curr.Y2, cand.Y2) - Math.Max(curr.Y1, cand.Y1);
                    float iou = curr.Iou(cand);
                    bool shouldMerge = (ox > -12f && oy > -16f) &&
                        (iou > 0.08f || (ox > Math.Min(curr.Width, cand.Width) * 0.35f && oy > -16f));
                    if (shouldMerge)
                    {
                        curr = new BoundingBox(
                            Math.Min(curr.X1, cand.X1),
                            Math.Min(curr.Y1, cand.Y1),
                            Math.Max(curr.X2, cand.X2),
                            Math.Max(curr.Y2, cand.Y2)
                        );
                        remaining.RemoveAt(i);
                        changed = true;
                    }
                    else
                    {
                        i++;
                    }
                }
            }
            merged.Add(curr);
        }

        return merged;
    }
}
