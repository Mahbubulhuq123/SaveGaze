using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

/// <summary>
/// Manages click-through, WDA_EXCLUDEFROMCAPTURE layered blur overlays, full-screen Intimate Shield,
/// transient toast banners, and unclosable 5-minute Cross-Block Lockout overlays on Windows.
/// </summary>
public sealed class OverlayManager : IDisposable
{
    private readonly Control _uiInvoker;
    private readonly BlurOverlayForm _blurForm;
    private IntimateShieldForm? _shieldForm;
    private BlockBannerForm? _bannerForm;
    private LockoutOverlayForm? _lockoutForm;
    private readonly System.Windows.Forms.Timer _bannerHideTimer;

    private BlurStyle _blurStyle = BlurStyle.SolidBlack;
    private int _blurOpacity = 100;
    private IReadOnlyList<BoundingBox> _regions = [];
    private IReadOnlyList<BoundingBox> _protectedFaces = [];

    public bool IsOverlayActive { get; private set; }
    public event Action<bool>? VisibilityChanged;

    public OverlayManager(Control uiInvoker)
    {
        _uiInvoker = uiInvoker;
        _blurForm = new BlurOverlayForm();
        _blurForm.Show();
        _blurForm.ApplyCaptureExclusion();
        IsOverlayActive = _blurForm.IsExcludedFromCapture;
        VisibilityChanged?.Invoke(IsOverlayActive);

        _bannerHideTimer = new System.Windows.Forms.Timer { Interval = 2600 };
        _bannerHideTimer.Tick += (_, _) =>
        {
            _bannerHideTimer.Stop();
            HideBannerImmediate();
        };
    }

    private void OnUi(Action action)
    {
        if (_uiInvoker.IsDisposed) return;
        if (_uiInvoker.InvokeRequired)
        {
            try { _uiInvoker.BeginInvoke(action); } catch { }
        }
        else
        {
            action();
        }
    }

    public void SetStyleAndOpacity(BlurStyle style, int opacityPercent)
    {
        OnUi(() =>
        {
            _blurStyle = style;
            _blurOpacity = Math.Clamp(opacityPercent, 50, 100);
            _blurForm.RenderRegions(_regions, _protectedFaces, _blurStyle, _blurOpacity);
        });
    }

    public void UpdateBlurRegions(IReadOnlyList<BoundingBox> regions, IReadOnlyList<BoundingBox> protectedFaces)
    {
        OnUi(() =>
        {
            _regions = regions;
            _protectedFaces = protectedFaces;
            if (!_blurForm.Visible && regions.Count > 0) _blurForm.Show();
            _blurForm.RenderRegions(regions, protectedFaces, _blurStyle, _blurOpacity);
            IsOverlayActive = true;
            VisibilityChanged?.Invoke(true);
        });
    }

    public void ClearBlurRegions()
    {
        OnUi(() =>
        {
            if (_regions.Count == 0 && _protectedFaces.Count == 0) return;
            _regions = [];
            _protectedFaces = [];
            _blurForm.RenderRegions(_regions, _protectedFaces, _blurStyle, _blurOpacity);
        });
    }

    public void ShowIntimateShield(string reason, string actionDetail)
    {
        OnUi(() =>
        {
            _shieldForm?.Close();
            _shieldForm?.Dispose();
            _shieldForm = new IntimateShieldForm(reason, actionDetail);
            _shieldForm.Show();
            _shieldForm.ApplyCaptureExclusion();
        });
    }

    public void HideIntimateShield()
    {
        OnUi(() =>
        {
            if (_shieldForm != null)
            {
                _shieldForm.Close();
                _shieldForm.Dispose();
                _shieldForm = null;
            }
        });
    }

    public void ShowBlockBanner(string title, string detail)
    {
        OnUi(() =>
        {
            HideBannerImmediate();
            _bannerForm = new BlockBannerForm(title, detail);
            _bannerForm.Show();
            _bannerForm.ApplyCaptureExclusion();
            _bannerHideTimer.Stop();
            _bannerHideTimer.Start();
        });
    }

    private void HideBannerImmediate()
    {
        if (_bannerForm != null)
        {
            _bannerForm.Close();
            _bannerForm.Dispose();
            _bannerForm = null;
        }
    }

    public void SyncLockout(long lockoutUntilMs, string storedPasswordHash, Action onUnlockedEarly, Action onExpired)
    {
        OnUi(() =>
        {
            long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (lockoutUntilMs > nowMs)
            {
                if (_lockoutForm == null || _lockoutForm.IsDisposed)
                {
                    _lockoutForm = new LockoutOverlayForm(lockoutUntilMs, storedPasswordHash, onUnlockedEarly, onExpired);
                    _lockoutForm.Show();
                }
                else
                {
                    _lockoutForm.UpdateUntil(lockoutUntilMs);
                }
            }
            else if (_lockoutForm != null && !_lockoutForm.IsDisposed)
            {
                _lockoutForm.AllowClose();
                _lockoutForm.Close();
                _lockoutForm.Dispose();
                _lockoutForm = null;
            }
        });
    }

    public void Dispose()
    {
        _bannerHideTimer.Dispose();
        OnUi(() =>
        {
            HideBannerImmediate();
            if (_shieldForm != null) { _shieldForm.Close(); _shieldForm.Dispose(); }
            if (_lockoutForm != null) { _lockoutForm.AllowClose(); _lockoutForm.Close(); _lockoutForm.Dispose(); }
            _blurForm.Close();
            _blurForm.Dispose();
        });
    }

    private sealed class BlurOverlayForm : Form
    {
        public bool IsExcludedFromCapture { get; private set; }
        private Rectangle _virtualBounds;
        private IReadOnlyList<BoundingBox> _drawRegions = [];
        private BlurStyle _drawStyle = BlurStyle.SolidBlack;
        private int _drawOpacity = 100;

        public BlurOverlayForm()
        {
            _virtualBounds = SystemInformation.VirtualScreen;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Bounds = _virtualBounds;
            BackColor = Color.Black;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint | ControlStyles.OptimizedDoubleBuffer, true);
            Region = new Region(Rectangle.Empty);
        }

        protected override bool ShowWithoutActivation => true;

        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= NativeMethods.WS_EX_LAYERED |
                              NativeMethods.WS_EX_TRANSPARENT |
                              NativeMethods.WS_EX_TOOLWINDOW |
                              NativeMethods.WS_EX_TOPMOST |
                              NativeMethods.WS_EX_NOACTIVATE;
                return cp;
            }
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            // DWM requires layered surface initialization via SetLayeredWindowAttributes before WDA_EXCLUDEFROMCAPTURE succeeds
            NativeMethods.SetLayeredWindowAttributes(Handle, 0, 255, NativeMethods.LWA_ALPHA);
        }

        public void ApplyCaptureExclusion()
        {
            if (IsHandleCreated)
            {
                NativeMethods.SetLayeredWindowAttributes(Handle, 0, (byte)(_drawOpacity * 255 / 100), NativeMethods.LWA_ALPHA);
                IsExcludedFromCapture = NativeMethods.SetWindowDisplayAffinity(Handle, NativeMethods.WDA_EXCLUDEFROMCAPTURE);
            }
        }

        public void RenderRegions(
            IReadOnlyList<BoundingBox> regions,
            IReadOnlyList<BoundingBox> protectedFaces,
            BlurStyle style,
            int opacityPercent)
        {
            if (!IsHandleCreated) return;
            var vs = SystemInformation.VirtualScreen;
            if (vs != _virtualBounds)
            {
                _virtualBounds = vs;
                Bounds = vs;
            }

            _drawRegions = regions;
            _drawStyle = style;
            _drawOpacity = Math.Clamp(opacityPercent, 50, 100);

            byte alphaByte = (byte)(_drawOpacity * 255 / 100);
            NativeMethods.SetLayeredWindowAttributes(Handle, 0, alphaByte, NativeMethods.LWA_ALPHA);
            if (!IsExcludedFromCapture)
            {
                IsExcludedFromCapture = NativeMethods.SetWindowDisplayAffinity(Handle, NativeMethods.WDA_EXCLUDEFROMCAPTURE);
            }

            if (regions.Count == 0)
            {
                var old = Region;
                Region = new Region(Rectangle.Empty);
                old?.Dispose();
                return;
            }

            var newRegion = new Region(Rectangle.Empty);
            foreach (var box in regions)
            {
                float left = Math.Clamp(box.X1 - vs.Left, 0f, vs.Width);
                float top = Math.Clamp(box.Y1 - vs.Top, 0f, vs.Height);
                float right = Math.Clamp(box.X2 - vs.Left, 0f, vs.Width);
                float bottom = Math.Clamp(box.Y2 - vs.Top, 0f, vs.Height);
                if (right <= left || bottom <= top) continue;

                using var path = CreateRoundedRect(left, top, right - left, bottom - top, 14f);
                newRegion.Union(path);
            }

            // Exclude protected upper faces so eyes/identity stay clear while throat/neck/body blur
            foreach (var f in protectedFaces)
            {
                var faceRect = new Rectangle(
                    Math.Max(0, (int)(f.X1 - vs.Left - 4f)),
                    Math.Max(0, (int)(f.Y1 - vs.Top - 4f)),
                    Math.Max(1, (int)MathF.Ceiling(f.Width + 8f)),
                    Math.Max(1, (int)MathF.Ceiling(f.Height + 4f)));
                newRegion.Exclude(faceRect);
            }

            var prev = Region;
            Region = newRegion;
            prev?.Dispose();
            Invalidate();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            if (_drawRegions.Count == 0) return;

            var g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;

            Color fillColor = _drawStyle switch
            {
                BlurStyle.DarkFrosted => Color.FromArgb(18, 20, 24),
                BlurStyle.LightCensor => Color.FromArgb(240, 240, 245),
                _ => Color.Black
            };
            g.Clear(fillColor);

            if (_drawStyle == BlurStyle.SolidBlack) return;

            Color frostColor = _drawStyle == BlurStyle.DarkFrosted
                ? Color.FromArgb(32, 255, 255, 255)
                : Color.FromArgb(40, 255, 255, 255);
            Color borderColor = _drawStyle == BlurStyle.DarkFrosted
                ? Color.FromArgb(90, 255, 255, 255)
                : Color.FromArgb(130, 160, 160, 170);

            using var frostBrush = new SolidBrush(frostColor);
            using var borderPen = new Pen(borderColor, 2.5f);

            foreach (var box in _drawRegions)
            {
                float left = Math.Clamp(box.X1 - _virtualBounds.Left, 0f, _virtualBounds.Width);
                float top = Math.Clamp(box.Y1 - _virtualBounds.Top, 0f, _virtualBounds.Height);
                float right = Math.Clamp(box.X2 - _virtualBounds.Left, 0f, _virtualBounds.Width);
                float bottom = Math.Clamp(box.Y2 - _virtualBounds.Top, 0f, _virtualBounds.Height);
                if (right <= left || bottom <= top) continue;

                using var innerPath = CreateRoundedRect(left + 2f, top + 2f, Math.Max(1f, right - left - 4f), Math.Max(1f, bottom - top - 4f), 12f);
                g.FillPath(frostBrush, innerPath);
                using var outerPath = CreateRoundedRect(left, top, right - left, bottom - top, 14f);
                g.DrawPath(borderPen, outerPath);
            }
        }

        private static GraphicsPath CreateRoundedRect(float x, float y, float w, float h, float r)
        {
            float d = Math.Min(r * 2f, Math.Min(w, h));
            var path = new GraphicsPath();
            if (d <= 1f)
            {
                path.AddRectangle(new RectangleF(x, y, w, h));
                return path;
            }
            path.AddArc(x, y, d, d, 180, 90);
            path.AddArc(x + w - d, y, d, d, 270, 90);
            path.AddArc(x + w - d, y + h - d, d, d, 0, 90);
            path.AddArc(x, y + h - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }
    }

    private sealed class IntimateShieldForm : Form
    {
        public IntimateShieldForm(string reason, string detail)
        {
            var vs = SystemInformation.VirtualScreen;
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Bounds = vs;
            BackColor = Color.FromArgb(8, 12, 22);
            Opacity = 0.98;

            var lblTitle = new Label
            {
                Text = $"{BrandConfig.AppName} Shield Active",
                Font = new Font("Segoe UI", 22f, FontStyle.Bold),
                ForeColor = Color.FromArgb(244, 63, 94),
                AutoSize = false,
                TextAlign = ContentAlignment.MiddleCenter,
                Dock = DockStyle.Top,
                Height = 140,
                Padding = new Padding(0, 80, 0, 0)
            };
            var lblReason = new Label
            {
                Text = $"{reason}\n{detail}",
                Font = new Font("Segoe UI", 13f),
                ForeColor = Color.FromArgb(226, 232, 240),
                AutoSize = false,
                TextAlign = ContentAlignment.TopCenter,
                Dock = DockStyle.Fill,
                Padding = new Padding(40, 12, 40, 0)
            };
            Controls.Add(lblReason);
            Controls.Add(lblTitle);
        }

        protected override bool ShowWithoutActivation => true;
        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= NativeMethods.WS_EX_TRANSPARENT |
                              NativeMethods.WS_EX_TOOLWINDOW |
                              NativeMethods.WS_EX_TOPMOST |
                              NativeMethods.WS_EX_NOACTIVATE;
                return cp;
            }
        }

        public void ApplyCaptureExclusion()
        {
            if (IsHandleCreated)
                NativeMethods.SetWindowDisplayAffinity(Handle, NativeMethods.WDA_EXCLUDEFROMCAPTURE);
        }
    }

    private sealed class BlockBannerForm : Form
    {
        public BlockBannerForm(string title, string detail)
        {
            var area = Screen.PrimaryScreen?.WorkingArea ?? new Rectangle(0, 0, 1280, 720);
            FormBorderStyle = FormBorderStyle.None;
            ShowInTaskbar = false;
            TopMost = true;
            StartPosition = FormStartPosition.Manual;
            Size = new Size(460, 86);
            Location = new Point(area.Left + (area.Width - Width) / 2, area.Top + 22);
            BackColor = Color.FromArgb(15, 23, 42);
            Opacity = 0.96;

            var lblT = new Label
            {
                Text = title,
                Font = new Font("Segoe UI", 10.5f, FontStyle.Bold),
                ForeColor = Color.FromArgb(244, 63, 94),
                Location = new Point(16, 12),
                Size = new Size(428, 24)
            };
            var lblD = new Label
            {
                Text = detail,
                Font = new Font("Segoe UI", 9f),
                ForeColor = Color.FromArgb(226, 232, 240),
                Location = new Point(16, 38),
                Size = new Size(428, 38)
            };
            Controls.Add(lblT);
            Controls.Add(lblD);
        }

        protected override bool ShowWithoutActivation => true;
        protected override CreateParams CreateParams
        {
            get
            {
                var cp = base.CreateParams;
                cp.ExStyle |= NativeMethods.WS_EX_TRANSPARENT |
                              NativeMethods.WS_EX_TOOLWINDOW |
                              NativeMethods.WS_EX_TOPMOST |
                              NativeMethods.WS_EX_NOACTIVATE;
                return cp;
            }
        }

        public void ApplyCaptureExclusion()
        {
            if (IsHandleCreated)
                NativeMethods.SetWindowDisplayAffinity(Handle, NativeMethods.WDA_EXCLUDEFROMCAPTURE);
        }
    }
}
