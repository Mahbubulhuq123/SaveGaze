using System.Diagnostics;
using System.Drawing;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

public sealed class MainForm : Form
{
    private AppConfig _config;
    private readonly OverlayManager _overlay;
    private readonly ContextExitController _exitController;
    private readonly WindowGuardService _guardService;

    private NudeNetOnnxDetector? _nudeDetector;
    private GenderPersonOnnxDetector? _genderDetector;
    private IntimateSceneOnnxDetector? _sceneDetector;
    private MobileClipIntimateOnnxDetector? _clipDetector;
    private AudioIntimateOnnxDetector? _audioDetector;
    private WasapiAudioMonitor? _audioMonitor;
    private ScreenScannerService? _scanner;

    private readonly NotifyIcon _trayIcon;
    private readonly ToolStripMenuItem _trayStatusItem;
    private readonly ToolStripMenuItem _trayToggleItem;
    private readonly System.Windows.Forms.Timer _uiRefreshTimer;
    private bool _allowExit;
    private bool _isBindingUi;

    // Header controls
    private Label _lblHeaderStatus = null!;
    private Button _btnMasterToggle = null!;

    // Home / Health controls
    private Label _lblHomeHero = null!;
    private Label _lblHealthAi = null!;
    private Label _lblHealthOverlay = null!;
    private Label _lblHealthAudio = null!;
    private Label _lblHealthSkip = null!;
    private Label _lblHealthScan = null!;
    private Label _lblHealthBlocks = null!;
    private TextBox _txtQuickSite = null!;
    private TextBox _txtQuickKw = null!;
    private Label _lblQuickFeedback = null!;

    // AI & Blur controls
    private RadioButton _rbGenderFemale = null!;
    private RadioButton _rbGenderMale = null!;
    private RadioButton _rbGenderBoth = null!;
    private CheckBox _chkBlurFaces = null!;
    private TrackBar _trkSensitivity = null!;
    private Label _lblSensitivityVal = null!;
    private RadioButton _rbStyleBlack = null!;
    private RadioButton _rbStyleFrosted = null!;
    private RadioButton _rbStyleLight = null!;
    private TrackBar _trkOpacity = null!;
    private Label _lblOpacityVal = null!;
    private TrackBar _trkInterval = null!;
    private Label _lblIntervalVal = null!;

    // Intimate Guard controls
    private CheckBox _chkIntimateGuard = null!;
    private CheckBox _chkIntimateAudio = null!;
    private CheckBox _chkAutoMovieAudio = null!;
    private CheckBox _chkCommunitySkip = null!;
    private Label _lblSkipLiveInfo = null!;
    private ListBox _lstCustomSkips = null!;
    private RadioButton _rbActionSmart = null!;
    private RadioButton _rbActionBack = null!;
    private RadioButton _rbDrmWarn = null!;
    private RadioButton _rbDrmBlock = null!;
    private RadioButton _rbDrmOff = null!;

    // Guard & Blocklist controls
    private CheckBox _chkWebsiteBlocker = null!;
    private CheckBox _chkKeywordBlocker = null!;
    private CheckBox _chkAppBlocker = null!;
    private CheckBox _chkBlockIncognito = null!;
    private CheckBox _chkCrossBlock = null!;
    private CheckBox _chkSocialBlock = null!;
    private CheckBox _chkTamperGuard = null!;
    private CheckBox _chkHostsBlock = null!;
    private TextBox _txtAddKw = null!;
    private TextBox _txtAddSite = null!;
    private TextBox _txtAddApp = null!;
    private ListBox _lstKeywords = null!;
    private ListBox _lstSites = null!;
    private ListBox _lstApps = null!;

    // Security & Updates controls
    private TextBox _txtCurPw = null!;
    private TextBox _txtNewPw = null!;
    private Label _lblPwFeedback = null!;
    private TextBox _txtExtId = null!;
    private CheckBox _chkAutoUpdates = null!;
    private Label _lblUpdateStatus = null!;
    private Button _btnCheckUpdates = null!;

    private static readonly Color BgSlate = Color.FromArgb(15, 23, 42);
    private static readonly Color CardSlate = Color.FromArgb(30, 41, 59);
    private static readonly Color SubCardSlate = Color.FromArgb(24, 34, 53);

    public MainForm(bool startMinimized)
    {
        _config = AppConfig.Load();
        _overlay = new OverlayManager(this);
        _overlay.SetStyleAndOpacity(_config.BlurStyle, _config.BlurOpacity);

        _exitController = new ContextExitController(() => _config, _overlay);
        _guardService = new WindowGuardService(
            this,
            () => _config,
            cfg => _config = cfg,
            _overlay,
            _exitController);

        InitializeUi();
        BindConfigToUi();

        _trayStatusItem = new ToolStripMenuItem("Status: Initializing AI...") { Enabled = false };
        _trayToggleItem = new ToolStripMenuItem("Pause Protection", null, (_, _) => ToggleMasterProtection());
        _trayIcon = BuildTrayIcon();

        _guardService.Start();

        _uiRefreshTimer = new System.Windows.Forms.Timer { Interval = 1500 };
        _uiRefreshTimer.Tick += (_, _) => RefreshHealthLabels();
        _uiRefreshTimer.Start();

        Load += async (_, _) =>
        {
            if (startMinimized)
            {
                Hide();
                ShowInTaskbar = false;
            }
            await InitializeAiEnginesAsync();
            if (_config.AutoCheckUpdates)
            {
                await CheckUpdatesAsync(manual: false);
            }
        };

        FormClosing += OnMainFormClosing;
    }

    private NotifyIcon BuildTrayIcon()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add($"Open {BrandConfig.DesktopDisplayName}", null, (_, _) => RestoreWindow());
        menu.Items.Add("-");
        menu.Items.Add(_trayStatusItem);
        menu.Items.Add(_trayToggleItem);
        menu.Items.Add("Trigger 5-Min Cooldown Lockout", null, (_, _) => TriggerManualLockout());
        menu.Items.Add("Check for Updates...", null, async (_, _) => { RestoreWindow(); await CheckUpdatesAsync(manual: true); });
        menu.Items.Add("-");
        menu.Items.Add($"Uninstall {BrandConfig.AppName}...", null, (_, _) => PromptUninstall());

        Icon? ico = null;
        string icoPath = Path.Combine(AppContext.BaseDirectory, "assets", "SaveGaze.ico");
        if (File.Exists(icoPath))
        {
            try { ico = new Icon(icoPath); } catch { }
        }

        var ni = new NotifyIcon
        {
            Text = $"{BrandConfig.DesktopDisplayName} — Protecting",
            Icon = ico ?? SystemIcons.Shield,
            ContextMenuStrip = menu,
            Visible = true
        };
        ni.DoubleClick += (_, _) => RestoreWindow();
        return ni;
    }

    private void RestoreWindow()
    {
        ShowInTaskbar = true;
        Show();
        WindowState = FormWindowState.Normal;
        BringToFront();
        Activate();
    }

    private void InitializeUi()
    {
        Text = $"{BrandConfig.DesktopDisplayName} ({BrandConfig.ArabicTag}) — v{BrandConfig.AppVersion}";
        Size = new Size(980, 700);
        MinimumSize = new Size(860, 620);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = BgSlate;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        string icoPath = Path.Combine(AppContext.BaseDirectory, "assets", "SaveGaze.ico");
        if (File.Exists(icoPath))
        {
            try { Icon = new Icon(icoPath); } catch { }
        }

        // Top Header
        var header = new Panel { Dock = DockStyle.Top, Height = 74, BackColor = Color.FromArgb(22, 33, 62) };
        string pngPath = Path.Combine(AppContext.BaseDirectory, "assets", "SaveGaze-128.png");
        if (File.Exists(pngPath))
        {
            try
            {
                header.Controls.Add(new PictureBox
                {
                    Image = Image.FromFile(pngPath),
                    SizeMode = PictureBoxSizeMode.Zoom,
                    Location = new Point(16, 14),
                    Size = new Size(44, 44)
                });
            }
            catch { }
        }

        header.Controls.Add(new Label
        {
            Text = $"{BrandConfig.DesktopDisplayName}  ({BrandConfig.ArabicTag})",
            Font = new Font("Segoe UI", 14f, FontStyle.Bold),
            ForeColor = Color.FromArgb(248, 250, 252),
            Location = new Point(70, 12),
            AutoSize = true
        });
        _lblHeaderStatus = new Label
        {
            Text = BrandConfig.Subtitle,
            Font = new Font("Segoe UI", 8.5f),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(72, 40),
            AutoSize = true
        };
        header.Controls.Add(_lblHeaderStatus);

        _btnMasterToggle = MakeButton("Pause Shield", Color.FromArgb(109, 40, 217), new Size(130, 34));
        _btnMasterToggle.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _btnMasterToggle.Location = new Point(header.Width - 280, 20);
        _btnMasterToggle.Click += (_, _) => ToggleMasterProtection();
        header.Controls.Add(_btnMasterToggle);

        var btnLock = MakeButton("5-Min Lockout", Color.FromArgb(225, 29, 72), new Size(125, 34));
        btnLock.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        btnLock.Location = new Point(header.Width - 140, 20);
        btnLock.Click += (_, _) => TriggerManualLockout();
        header.Controls.Add(btnLock);

        Controls.Add(header);

        // Tabs
        var tabs = new TabControl { Dock = DockStyle.Fill, Font = new Font("Segoe UI", 9.5f) };
        var tabHome = new TabPage("Home & Health") { BackColor = BgSlate, AutoScroll = true };
        var tabAi = new TabPage("AI Screen Shield") { BackColor = BgSlate, AutoScroll = true };
        var tabIntimate = new TabPage("Intimate & Audio Guard") { BackColor = BgSlate, AutoScroll = true };
        var tabGuard = new TabPage("Guard & Blocklists") { BackColor = BgSlate, AutoScroll = true };
        var tabSecurity = new TabPage("Security & Updates") { BackColor = BgSlate, AutoScroll = true };

        BuildHomeTab(tabHome);
        BuildAiTab(tabAi);
        BuildIntimateTab(tabIntimate);
        BuildGuardTab(tabGuard);
        BuildSecurityTab(tabSecurity);

        tabs.TabPages.AddRange([tabHome, tabAi, tabIntimate, tabGuard, tabSecurity]);
        Controls.Add(tabs);
    }

    private void BuildHomeTab(TabPage page)
    {
        var tlp = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(16), RowCount = 3, ColumnCount = 1 };
        tlp.RowStyles.Add(new RowStyle(SizeType.Absolute, 96));
        tlp.RowStyles.Add(new RowStyle(SizeType.Absolute, 185));
        tlp.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        // 1. Hero status card
        var hero = new Panel { Dock = DockStyle.Fill, BackColor = CardSlate, Padding = new Padding(14), Margin = new Padding(0, 0, 0, 10) };
        _lblHomeHero = new Label
        {
            Text = "Initializing On-Device ONNX Protection...",
            Font = new Font("Segoe UI", 12.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(74, 222, 128),
            Location = new Point(14, 12),
            AutoSize = true
        };
        hero.Controls.Add(_lblHomeHero);
        hero.Controls.Add(new Label
        {
            Text = "100% on-device AI screen shield & content guard across Chrome, Brave, Edge, Firefox, VLC, Stremio, Nuvio, and Telegram. No screen frames or audio leave your PC.",
            Font = new Font("Segoe UI", 8.8f),
            ForeColor = Color.FromArgb(203, 213, 225),
            Location = new Point(16, 42),
            Size = new Size(880, 42)
        });
        tlp.Controls.Add(hero, 0, 0);

        // 2. Quick Block card
        var quick = new Panel { Dock = DockStyle.Fill, BackColor = SubCardSlate, Padding = new Padding(14), Margin = new Padding(0, 0, 0, 10) };
        quick.Controls.Add(new Label
        {
            Text = "QUICK BLOCK WEBSITES & KEYWORDS",
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            ForeColor = Color.FromArgb(56, 189, 248),
            Location = new Point(14, 10),
            AutoSize = true
        });

        quick.Controls.Add(new Label { Text = "Block Website:", Font = new Font("Segoe UI", 9f, FontStyle.Bold), Location = new Point(14, 44), Size = new Size(115, 22) });
        _txtQuickSite = new TextBox { Location = new Point(135, 41), Size = new Size(340, 26), BackColor = BgSlate, ForeColor = Color.White };
        var btnAddSite = MakeButton("Block Website", Color.FromArgb(225, 29, 72), new Size(130, 28));
        btnAddSite.Location = new Point(485, 40);
        btnAddSite.Click += (_, _) => QuickAddWebsite();
        _txtQuickSite.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; QuickAddWebsite(); } };
        quick.Controls.Add(_txtQuickSite);
        quick.Controls.Add(btnAddSite);

        quick.Controls.Add(new Label { Text = "Block Keyword:", Font = new Font("Segoe UI", 9f, FontStyle.Bold), Location = new Point(14, 84), Size = new Size(115, 22) });
        _txtQuickKw = new TextBox { Location = new Point(135, 81), Size = new Size(340, 26), BackColor = BgSlate, ForeColor = Color.White };
        var btnAddKw = MakeButton("Block Keyword", Color.FromArgb(225, 29, 72), new Size(130, 28));
        btnAddKw.Location = new Point(485, 80);
        btnAddKw.Click += (_, _) => QuickAddKeyword();
        _txtQuickKw.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; QuickAddKeyword(); } };
        quick.Controls.Add(_txtQuickKw);
        quick.Controls.Add(btnAddKw);

        _lblQuickFeedback = new Label
        {
            Location = new Point(14, 122),
            Size = new Size(760, 44),
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            ForeColor = Color.FromArgb(74, 222, 128)
        };
        quick.Controls.Add(_lblQuickFeedback);
        tlp.Controls.Add(quick, 0, 1);

        // 3. Protection Health card
        var health = new Panel { Dock = DockStyle.Fill, BackColor = CardSlate, Padding = new Padding(14), AutoScroll = true };
        health.Controls.Add(new Label
        {
            Text = "LIVE PROTECTION HEALTH",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(14, 12),
            AutoSize = true
        });
        _lblHealthAi = CreateHealthRow(health, 42, "ONNX AI Engines:", "Loading best_320s + pordav4x3 + image_safety_xs + mobileclip + yamnet...");
        _lblHealthOverlay = CreateHealthRow(health, 74, "Capture-Excluded Overlay:", "Checking WDA_EXCLUDEFROMCAPTURE...");
        _lblHealthAudio = CreateHealthRow(health, 106, "WASAPI Playback Audio AI:", "Waiting...");
        _lblHealthSkip = CreateHealthRow(health, 138, "Community Timestamp Skip:", "Checking...");
        _lblHealthScan = CreateHealthRow(health, 170, "Last Screen Scan:", "Waiting for first frame...");
        _lblHealthBlocks = CreateHealthRow(health, 202, "Guard Interceptions:", "0 attempts blocked");
        tlp.Controls.Add(health, 0, 2);

        page.Controls.Add(tlp);
    }

    private void BuildAiTab(TabPage page)
    {
        var panel = new Panel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(18), BackColor = BgSlate };

        // Gender card
        var cardGender = MakeCard(14, 14, 880, 138);
        cardGender.Controls.Add(MakeTitle("Gender Filter (PordaV4x3 + NudeNet Hybrid)", 14, 10));
        _rbGenderFemale = MakeRadio(cardGender, "Female Only — Blur female bodies (plus face if Face Blurring ON)", 16, 40);
        _rbGenderMale = MakeRadio(cardGender, "Male Only — Blur male bodies (plus face if Face Blurring ON)", 16, 68);
        _rbGenderBoth = MakeRadio(cardGender, "Both — Blur semi-nude/nude bodies for both genders", 16, 96);
        _rbGenderFemale.CheckedChanged += (_, _) => SaveAiSettings();
        _rbGenderMale.CheckedChanged += (_, _) => SaveAiSettings();
        _rbGenderBoth.CheckedChanged += (_, _) => SaveAiSettings();
        panel.Controls.Add(cardGender);

        // Face Blur & Sensitivity card
        var cardFace = MakeCard(14, 164, 880, 195);
        cardFace.Controls.Add(MakeTitle("Face Blurring & Detection Sensitivity", 14, 10));
        _chkBlurFaces = new CheckBox
        {
            Text = "Face Blurring (When OFF: preserves eyes/upper face while blurring throat/neck up to jawline)",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            ForeColor = Color.White,
            Location = new Point(16, 42),
            Size = new Size(840, 26)
        };
        _chkBlurFaces.CheckedChanged += (_, _) =>
        {
            if (_isBindingUi) return;
            if (!_chkBlurFaces.Checked &&
                !VerificationDialogForm.VerifyProtectionChange("Disable Face Blurring", _config.PasswordHash, this))
            {
                _isBindingUi = true;
                _chkBlurFaces.Checked = true;
                _isBindingUi = false;
                return;
            }
            SaveAiSettings();
        };
        cardFace.Controls.Add(_chkBlurFaces);

        _lblSensitivityVal = new Label { Text = "Sensitivity Threshold: 0.25", Location = new Point(16, 82), Size = new Size(340, 22), ForeColor = Color.FromArgb(196, 181, 253), Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
        cardFace.Controls.Add(_lblSensitivityVal);
        _trkSensitivity = new TrackBar { Minimum = 15, Maximum = 45, TickFrequency = 5, Location = new Point(16, 108), Size = new Size(420, 40) };
        _trkSensitivity.Scroll += (_, _) => _lblSensitivityVal.Text = $"Sensitivity Threshold: {_trkSensitivity.Value / 100f:F2} (0.15 = Max Protection, 0.45 = Stricter)";
        _trkSensitivity.MouseUp += (_, _) => OnSensitivityCommitted();
        cardFace.Controls.Add(_trkSensitivity);

        _lblIntervalVal = new Label { Text = "Scan Interval: 320 ms", Location = new Point(470, 82), Size = new Size(360, 22), ForeColor = Color.FromArgb(56, 189, 248), Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
        cardFace.Controls.Add(_lblIntervalVal);
        _trkInterval = new TrackBar { Minimum = 200, Maximum = 800, TickFrequency = 100, Location = new Point(470, 108), Size = new Size(360, 40) };
        _trkInterval.Scroll += (_, _) => _lblIntervalVal.Text = $"Scan Interval: {_trkInterval.Value} ms";
        _trkInterval.MouseUp += (_, _) => SaveAiSettings();
        cardFace.Controls.Add(_trkInterval);
        panel.Controls.Add(cardFace);

        // Blur Style & Opacity card
        var cardStyle = MakeCard(14, 372, 880, 185);
        cardStyle.Controls.Add(MakeTitle("Overlay Blur Style & Opacity", 14, 10));
        _rbStyleBlack = MakeRadio(cardStyle, "Solid Black (HaramBlur style — pitch black opaque)", 16, 40);
        _rbStyleFrosted = MakeRadio(cardStyle, "Dark Frosted Glass (Deep translucent slate with subtle rim)", 16, 68);
        _rbStyleLight = MakeRadio(cardStyle, "Light Censor Card (Clean bright censor block)", 16, 96);
        _rbStyleBlack.CheckedChanged += (_, _) => SaveAiSettings();
        _rbStyleFrosted.CheckedChanged += (_, _) => SaveAiSettings();
        _rbStyleLight.CheckedChanged += (_, _) => SaveAiSettings();

        _lblOpacityVal = new Label { Text = "Blur Opacity: 100%", Location = new Point(490, 40), Size = new Size(340, 22), ForeColor = Color.FromArgb(196, 181, 253), Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
        cardStyle.Controls.Add(_lblOpacityVal);
        _trkOpacity = new TrackBar { Minimum = 50, Maximum = 100, TickFrequency = 10, Location = new Point(490, 68), Size = new Size(340, 40) };
        _trkOpacity.Scroll += (_, _) => _lblOpacityVal.Text = $"Blur Opacity: {_trkOpacity.Value}%";
        _trkOpacity.MouseUp += (_, _) => SaveAiSettings();
        cardStyle.Controls.Add(_trkOpacity);
        panel.Controls.Add(cardStyle);

        page.Controls.Add(panel);
    }

    private void BuildIntimateTab(TabPage page)
    {
        var panel = new Panel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(18), BackColor = BgSlate };

        var cardIntimate = MakeCard(14, 14, 880, 175);
        cardIntimate.Controls.Add(MakeTitle("Intimate Video, Scene & WASAPI Audio Guard", 14, 10));
        _chkIntimateGuard = MakeCheck(cardIntimate, "Auto-Action on Intimate Scenes (SwiftFormer + MobileCLIP2-S0 + kissing/bed pose + audio)", 16, 44);
        _chkIntimateGuard.CheckedChanged += (_, _) =>
        {
            if (_isBindingUi) return;
            if (!_chkIntimateGuard.Checked &&
                !VerificationDialogForm.VerifyProtectionChange("Disable Intimate Video Guard", _config.PasswordHash, this))
            {
                _isBindingUi = true;
                _chkIntimateGuard.Checked = true;
                _isBindingUi = false;
                return;
            }
            SaveIntimateSettings();
        };
        _chkIntimateAudio = MakeCheck(cardIntimate, "System Playback Audio AI (Windows WASAPI Loopback + yamnet.onnx; strictly visual-first gated)", 16, 82);
        _chkIntimateAudio.CheckedChanged += (_, _) => SaveIntimateSettings();
        _chkAutoMovieAudio = MakeCheck(cardIntimate, "Auto Audio Guard on Video Players (VLC, Stremio, Nuvio, MPV, Kodi, Browser video)", 16, 120);
        _chkAutoMovieAudio.CheckedChanged += (_, _) => SaveIntimateSettings();
        panel.Controls.Add(cardIntimate);

        var cardSkip = MakeCard(14, 202, 880, 226);
        cardSkip.Controls.Add(MakeTitle("Hybrid Community & Custom Timestamp Skip (CleanStream + MediaSession + MCF)", 14, 10));
        _chkCommunitySkip = MakeCheck(cardSkip, "Auto-Skip Known Scenes Using CleanStream & Custom Timestamps (Instant AI fallback if unlisted)", 16, 42);
        _chkCommunitySkip.CheckedChanged += (_, _) => SaveIntimateSettings();

        _lblSkipLiveInfo = new Label
        {
            Text = "Windows MediaSession Sync Ready (Chrome, Brave, Edge, Firefox, VLC, Stremio)",
            Location = new Point(16, 72),
            Size = new Size(845, 22),
            ForeColor = Color.FromArgb(56, 189, 248),
            Font = new Font("Segoe UI", 8.8f, FontStyle.Bold)
        };
        cardSkip.Controls.Add(_lblSkipLiveInfo);

        _lstCustomSkips = new ListBox
        {
            Location = new Point(16, 100),
            Size = new Size(650, 108),
            BackColor = BgSlate,
            ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle
        };
        cardSkip.Controls.Add(_lstCustomSkips);

        var btnAddCustomSkip = MakeButton("Add Timestamps / MCF...", Color.FromArgb(109, 40, 217), new Size(190, 32));
        btnAddCustomSkip.Location = new Point(676, 100);
        btnAddCustomSkip.Click += (_, _) => ShowAddCustomTimestampDialog();
        cardSkip.Controls.Add(btnAddCustomSkip);

        var btnDelCustomSkip = MakeButton("Remove Selected", Color.FromArgb(51, 65, 85), new Size(190, 32));
        btnDelCustomSkip.Location = new Point(676, 140);
        btnDelCustomSkip.Click += (_, _) => RemoveSelectedCustomSkip();
        cardSkip.Controls.Add(btnDelCustomSkip);
        panel.Controls.Add(cardSkip);

        var cardAction = MakeCard(14, 442, 880, 125);
        cardAction.Controls.Add(MakeTitle("When Intimate Scene Triggers", 14, 10));
        _rbActionSmart = MakeRadio(cardAction, "Smart Context (Reels/Shorts -> Next Reel; Movies/Players -> Endless +Skip Forward; Browser -> Close Tab)", 16, 42);
        _rbActionBack = MakeRadio(cardAction, "Always Exit Video (Blur First, Pause Playback & Close Tab / Back)", 16, 74);
        _rbActionSmart.CheckedChanged += (_, _) => SaveIntimateSettings();
        _rbActionBack.CheckedChanged += (_, _) => SaveIntimateSettings();
        panel.Controls.Add(cardAction);

        var cardDrm = MakeCard(14, 580, 880, 148);
        cardDrm.Controls.Add(MakeTitle("DRM Streaming Policy (Netflix, Prime Video, Hotstar, Chorki, Hoichoi)", 14, 10));
        _rbDrmWarn = MakeRadio(cardDrm, "Show Warning Notice once per session when DRM streaming may restrict capture", 16, 40);
        _rbDrmBlock = MakeRadio(cardDrm, "Auto-Block DRM Apps & Sites", 16, 70);
        _rbDrmOff = MakeRadio(cardDrm, "Off (Allow silently)", 16, 100);
        _rbDrmWarn.CheckedChanged += (_, _) => SaveDrmSetting(DrmAppPolicy.WarnOnly);
        _rbDrmBlock.CheckedChanged += (_, _) => SaveDrmSetting(DrmAppPolicy.AutoBlock);
        _rbDrmOff.CheckedChanged += (_, _) => SaveDrmSetting(DrmAppPolicy.Off);
        panel.Controls.Add(cardDrm);

        page.Controls.Add(panel);
    }

    private void BuildGuardTab(TabPage page)
    {
        var tlp = new TableLayoutPanel { Dock = DockStyle.Fill, Padding = new Padding(14), RowCount = 2, ColumnCount = 1 };
        tlp.RowStyles.Add(new RowStyle(SizeType.Absolute, 195));
        tlp.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var togglesCard = new Panel { Dock = DockStyle.Fill, BackColor = CardSlate, Padding = new Padding(12), Margin = new Padding(0, 0, 0, 10) };
        togglesCard.Controls.Add(MakeTitle("ContentGuard Protection Toggles (Turning OFF requires Challenge)", 12, 8));

        _chkWebsiteBlocker = MakeProtectedToggle(togglesCard, "Website & Domain Blocker", 14, 38, "Disable Website Blocker", v => _config.WebsiteBlockerEnabled = v);
        _chkKeywordBlocker = MakeProtectedToggle(togglesCard, "Keyword & Live Search Bar Blocker", 14, 72, "Disable Keyword Blocker", v => _config.KeywordBlockerEnabled = v);
        _chkAppBlocker = MakeProtectedToggle(togglesCard, "Blocked Apps Watchdog (.exe)", 14, 106, "Disable App Blocker", v => _config.AppBlockerEnabled = v);
        _chkBlockIncognito = MakeProtectedToggle(togglesCard, "Block Incognito / Private Browsing", 14, 140, "Allow Incognito Mode", v => { _config.BlockIncognito = v; if (v) InstallerEngine.EnforceIncognitoPolicies(); else InstallerEngine.RestoreIncognitoPolicies(); });

        _chkCrossBlock = MakeProtectedToggle(togglesCard, "5-in-10m Cross-Block Auto-Lockout", 440, 38, "Disable Auto-Lockout", v => _config.CrossBlockLockoutEnabled = v);
        _chkSocialBlock = MakeProtectedToggle(togglesCard, "Block Social Media Distractions", 440, 72, "Disable Social Distraction Block", v => _config.BlockSocialDistractions = v);
        _chkTamperGuard = MakeProtectedToggle(togglesCard, "Tamper, Extension & Uninstall Shield", 440, 106, "Disable Tamper Shield", v => _config.TamperGuardEnabled = v);
        _chkHostsBlock = MakeProtectedToggle(togglesCard, "System Hosts File Domain Blocking", 440, 140, "Disable Hosts Blocking", v => _config.BlockHostsFile = v);
        tlp.Controls.Add(togglesCard, 0, 0);

        var listsPanel = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 3, RowCount = 1 };
        listsPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        listsPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.33f));
        listsPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.34f));

        (_txtAddKw, _lstKeywords) = BuildListCard(listsPanel, 0, "Blocked Keywords",
            kw => { if (!_config.CustomKeywords.Contains(kw, StringComparer.OrdinalIgnoreCase)) { _config.CustomKeywords.Add(kw); SaveAndSync(); } },
            kw => { _config.CustomKeywords.Remove(kw); SaveAndSync(); });
        (_txtAddSite, _lstSites) = BuildListCard(listsPanel, 1, "Blocked Websites",
            site => { string clean = ContentGuardRules.NormalizeHost(site); if (!string.IsNullOrEmpty(clean) && !_config.CustomWebsites.Contains(clean, StringComparer.OrdinalIgnoreCase)) { _config.CustomWebsites.Add(clean); SaveAndSync(); } },
            site => { _config.CustomWebsites.Remove(site); SaveAndSync(); });
        (_txtAddApp, _lstApps) = BuildListCard(listsPanel, 2, "Blocked Apps (.exe)",
            app => { string clean = app.ToLowerInvariant().Replace(".exe", "").Trim(); if (!string.IsNullOrEmpty(clean) && !_config.CustomApps.Contains(clean, StringComparer.OrdinalIgnoreCase)) { _config.CustomApps.Add(clean); SaveAndSync(); } },
            app => { _config.CustomApps.Remove(app.Replace(".exe", "")); SaveAndSync(); });

        tlp.Controls.Add(listsPanel, 0, 1);
        page.Controls.Add(tlp);
    }

    private void BuildSecurityTab(TabPage page)
    {
        var panel = new Panel { Dock = DockStyle.Top, AutoSize = true, Padding = new Padding(18), BackColor = BgSlate };

        // Master Password Card
        var cardPw = MakeCard(14, 14, 880, 165);
        cardPw.Controls.Add(MakeTitle("Master Password Protection", 14, 10));
        cardPw.Controls.Add(new Label { Text = "Current Password:", Location = new Point(16, 48), Size = new Size(135, 22) });
        _txtCurPw = new TextBox { PasswordChar = '*', Location = new Point(155, 45), Size = new Size(220, 25), BackColor = BgSlate, ForeColor = Color.White };
        cardPw.Controls.Add(_txtCurPw);

        cardPw.Controls.Add(new Label { Text = "New Password:", Location = new Point(16, 84), Size = new Size(135, 22) });
        _txtNewPw = new TextBox { PasswordChar = '*', Location = new Point(155, 81), Size = new Size(220, 25), BackColor = BgSlate, ForeColor = Color.White };
        cardPw.Controls.Add(_txtNewPw);

        var btnPw = MakeButton("Set / Update Password", Color.FromArgb(56, 189, 248), new Size(175, 30));
        btnPw.ForeColor = Color.FromArgb(15, 23, 42);
        btnPw.Location = new Point(392, 79);
        btnPw.Click += (_, _) => UpdateMasterPassword();
        cardPw.Controls.Add(btnPw);

        _lblPwFeedback = new Label { Location = new Point(16, 120), Size = new Size(650, 26), Font = new Font("Segoe UI", 9f, FontStyle.Bold) };
        cardPw.Controls.Add(_lblPwFeedback);
        panel.Controls.Add(cardPw);

        // Browser Extension & Native Host Card
        var cardExt = MakeCard(14, 192, 880, 140);
        cardExt.Controls.Add(MakeTitle("Chrome / Brave / Edge Extension Sync", 14, 10));
        cardExt.Controls.Add(new Label { Text = "Extension ID:", Location = new Point(16, 48), Size = new Size(110, 22) });
        _txtExtId = new TextBox { Location = new Point(130, 45), Size = new Size(340, 25), BackColor = BgSlate, ForeColor = Color.White };
        cardExt.Controls.Add(_txtExtId);

        var btnBindExt = MakeButton("Register Native Host", Color.FromArgb(109, 40, 217), new Size(165, 30));
        btnBindExt.Location = new Point(485, 43);
        btnBindExt.Click += (_, _) =>
        {
            _config.ExtensionId = _txtExtId.Text.Trim();
            _config.Save();
            InstallerEngine.RegisterNativeMessagingHost(AppContext.BaseDirectory, _config.ExtensionId);
            MessageBox.Show($"Native Messaging registered for Chrome, Brave & Edge ({BrandConfig.NativeHostName}).",
                BrandConfig.DesktopDisplayName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        };
        cardExt.Controls.Add(btnBindExt);

        var btnOpenExt = MakeButton("Open Extension Folder", Color.FromArgb(51, 65, 85), new Size(175, 30));
        btnOpenExt.Location = new Point(665, 43);
        btnOpenExt.Click += (_, _) => OpenExtensionFolder();
        cardExt.Controls.Add(btnOpenExt);

        cardExt.Controls.Add(new Label
        {
            Text = "Load the SaveGaze extension folder in chrome://extensions or brave://extensions (Developer mode) to enable live search-bar keyword blocking & DNR domain blocking.",
            Location = new Point(16, 86),
            Size = new Size(840, 38),
            ForeColor = Color.FromArgb(148, 163, 184),
            Font = new Font("Segoe UI", 8.5f)
        });
        panel.Controls.Add(cardExt);

        // GitHub Release Auto-Updater + Uninstall Card
        var cardUpd = MakeCard(14, 344, 880, 185);
        cardUpd.Controls.Add(MakeTitle($"Updates & Uninstall ({BrandConfig.DesktopDisplayName} v{BrandConfig.AppVersion})", 14, 10));
        _chkAutoUpdates = MakeCheck(cardUpd, "Automatically check GitHub Releases (pc-v*) on launch", 16, 42);
        _chkAutoUpdates.CheckedChanged += (_, _) => { if (!_isBindingUi) { _config.AutoCheckUpdates = _chkAutoUpdates.Checked; _config.Save(); } };

        _btnCheckUpdates = MakeButton("Check for Updates", Color.FromArgb(109, 40, 217), new Size(170, 34));
        _btnCheckUpdates.Location = new Point(16, 80);
        _btnCheckUpdates.Click += async (_, _) => await CheckUpdatesAsync(manual: true);
        cardUpd.Controls.Add(_btnCheckUpdates);

        _lblUpdateStatus = new Label
        {
            Text = $"Current version: v{BrandConfig.AppVersion} (Branch: {BrandConfig.PcBranch})",
            Location = new Point(200, 88),
            Size = new Size(460, 24),
            ForeColor = Color.FromArgb(148, 163, 184)
        };
        cardUpd.Controls.Add(_lblUpdateStatus);

        var btnUninstall = MakeButton($"Uninstall {BrandConfig.AppName} (Challenge Required)", Color.FromArgb(225, 29, 72), new Size(310, 36));
        btnUninstall.Location = new Point(16, 130);
        btnUninstall.Click += (_, _) => PromptUninstall();
        cardUpd.Controls.Add(btnUninstall);

        panel.Controls.Add(cardUpd);
        page.Controls.Add(panel);
    }

    // ── Helpers & Lifecycle ────────────────────────────────────────────

    private async Task InitializeAiEnginesAsync()
    {
        try
        {
            await Task.Run(() =>
            {
                _nudeDetector = new NudeNetOnnxDetector();
                _genderDetector = new GenderPersonOnnxDetector();
                _sceneDetector = new IntimateSceneOnnxDetector();
                try { _clipDetector = new MobileClipIntimateOnnxDetector(); } catch { }
                _audioDetector = new AudioIntimateOnnxDetector();
                _audioMonitor = new WasapiAudioMonitor(_audioDetector);
                _scanner = new ScreenScannerService(
                    () => _config,
                    _overlay,
                    _nudeDetector,
                    _genderDetector,
                    _sceneDetector,
                    _audioMonitor,
                    _exitController,
                    _clipDetector);
            });
            _scanner!.Start();
            RefreshHealthLabels();
        }
        catch (Exception ex)
        {
            _lblHealthAi.Text = "Failed to initialize ONNX models: " + ex.Message;
            _lblHealthAi.ForeColor = Color.FromArgb(244, 63, 94);
        }
    }

    private void BindConfigToUi()
    {
        _isBindingUi = true;
        try
        {
            _rbGenderFemale.Checked = _config.GenderFilter == GenderFilter.FemaleOnly;
            _rbGenderMale.Checked = _config.GenderFilter == GenderFilter.MaleOnly;
            _rbGenderBoth.Checked = _config.GenderFilter == GenderFilter.Both;
            _chkBlurFaces.Checked = _config.BlurFaces;
            _trkSensitivity.Value = Math.Clamp((int)Math.Round(_config.Sensitivity * 100f), 15, 45);
            _lblSensitivityVal.Text = $"Sensitivity Threshold: {_config.Sensitivity:F2} (0.15 = Max Protection, 0.45 = Stricter)";
            _trkInterval.Value = Math.Clamp(_config.ScanIntervalMs, 200, 800);
            _lblIntervalVal.Text = $"Scan Interval: {_config.ScanIntervalMs} ms";
            _rbStyleBlack.Checked = _config.BlurStyle == BlurStyle.SolidBlack;
            _rbStyleFrosted.Checked = _config.BlurStyle == BlurStyle.DarkFrosted;
            _rbStyleLight.Checked = _config.BlurStyle == BlurStyle.LightCensor;
            _trkOpacity.Value = Math.Clamp(_config.BlurOpacity, 50, 100);
            _lblOpacityVal.Text = $"Blur Opacity: {_config.BlurOpacity}%";

            _chkIntimateGuard.Checked = _config.IntimateGuardEnabled;
            _chkIntimateAudio.Checked = _config.IntimateAudioEnabled;
            _chkAutoMovieAudio.Checked = _config.AutoMovieAudioEnabled;
            _chkCommunitySkip.Checked = _config.CommunitySkipEnabled;
            _rbActionSmart.Checked = _config.IntimateActionMode == IntimateActionMode.SmartContext;
            _rbActionBack.Checked = _config.IntimateActionMode == IntimateActionMode.BackOnly;
            _rbDrmWarn.Checked = _config.DrmAppPolicy == DrmAppPolicy.WarnOnly;
            _rbDrmBlock.Checked = _config.DrmAppPolicy == DrmAppPolicy.AutoBlock;
            _rbDrmOff.Checked = _config.DrmAppPolicy == DrmAppPolicy.Off;

            _chkWebsiteBlocker.Checked = _config.WebsiteBlockerEnabled;
            _chkKeywordBlocker.Checked = _config.KeywordBlockerEnabled;
            _chkAppBlocker.Checked = _config.AppBlockerEnabled;
            _chkBlockIncognito.Checked = _config.BlockIncognito;
            _chkCrossBlock.Checked = _config.CrossBlockLockoutEnabled;
            _chkSocialBlock.Checked = _config.BlockSocialDistractions;
            _chkTamperGuard.Checked = _config.TamperGuardEnabled;
            _chkHostsBlock.Checked = _config.BlockHostsFile;

            RefreshListBoxes();
            _txtExtId.Text = _config.ExtensionId;
            _chkAutoUpdates.Checked = _config.AutoCheckUpdates;
            UpdateMasterButtons();
        }
        finally
        {
            _isBindingUi = false;
        }
    }

    private void RefreshListBoxes()
    {
        _lstKeywords.Items.Clear();
        foreach (string k in _config.CustomKeywords) _lstKeywords.Items.Add(k);
        _lstSites.Items.Clear();
        foreach (string s in _config.CustomWebsites) _lstSites.Items.Add(s);
        _lstApps.Items.Clear();
        foreach (string a in _config.CustomApps) _lstApps.Items.Add(a + ".exe");
        _lstCustomSkips.Items.Clear();
        foreach (var entry in _config.CustomSkipEntries)
        {
            string segSummary = string.Join(", ", entry.Segments.Select(s => $"{CommunitySkipRules.FormatMs(s.StartMs)}-{CommunitySkipRules.FormatMs(s.EndMs)}"));
            _lstCustomSkips.Items.Add($"{entry.DisplayTitle}  [{segSummary}]");
        }
    }

    private void UpdateMasterButtons()
    {
        bool on = _config.ProtectionEnabled;
        _btnMasterToggle.Text = on ? "Pause Shield" : "Resume Shield";
        _btnMasterToggle.BackColor = on ? Color.FromArgb(109, 40, 217) : Color.FromArgb(22, 163, 74);
        if (_trayToggleItem != null) _trayToggleItem.Text = on ? "Pause Protection" : "Resume Protection";
        _lblHomeHero.Text = on
            ? $"{BrandConfig.DesktopDisplayName} Protection Active — System-Wide AI Shield Armed"
            : $"{BrandConfig.DesktopDisplayName} Protection Paused — Tap Resume Shield to Re-Enable";
        _lblHomeHero.ForeColor = on ? Color.FromArgb(74, 222, 128) : Color.FromArgb(250, 204, 21);
    }

    private void RefreshHealthLabels()
    {
        var snap = _scanner?.Health;
        if (snap != null)
        {
            _lblHealthAi.Text = snap.DetectorReady
                ? (snap.ClipActive
                    ? "Ready (best_320s + pordav4x3 + image_safety_xs + mobileclip + yamnet)"
                    : "Ready (best_320s + pordav4x3 + image_safety_xs + yamnet)")
                : "Loading...";
            _lblHealthAi.ForeColor = snap.DetectorReady ? Color.FromArgb(74, 222, 128) : Color.FromArgb(250, 204, 21);
            _lblHealthOverlay.Text = _overlay.IsOverlayActive ? "Active & Capture-Excluded (WDA_EXCLUDEFROMCAPTURE)" : "Active (Layered Window)";
            _lblHealthOverlay.ForeColor = Color.FromArgb(74, 222, 128);
            _lblHealthAudio.Text = snap.AudioStatus;
            _lblHealthAudio.ForeColor = snap.AudioActive ? Color.FromArgb(74, 222, 128) : Color.FromArgb(148, 163, 184);
            string skipText = snap.ActiveSkipMatchInfo
                ?? (_config.CommunitySkipEnabled ? "Ready (CleanStream + Custom MCF + MediaSession)" : "Off");
            _lblHealthSkip.Text = skipText;
            _lblHealthSkip.ForeColor = snap.ActiveSkipMatchInfo != null ? Color.FromArgb(56, 189, 248) : Color.FromArgb(148, 163, 184);
            _lblSkipLiveInfo.Text = snap.ActiveSkipMatchInfo ?? "Windows MediaSession Sync Ready (Chrome, Brave, Edge, Firefox, VLC, Stremio)";
            long ageMs = Math.Max(0, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - snap.LastFrameAtMs);
            _lblHealthScan.Text = snap.LastFrameAtMs == 0 ? "Waiting..." : (ageMs < 2000 ? $"Just now ({snap.ActiveBlurBoxCount} active blur regions)" : $"{ageMs / 1000}s ago");
            _trayStatusItem.Text = _config.ProtectionEnabled ? $"Status: Active ({snap.ActiveBlurBoxCount} blurs)" : "Status: Paused";
        }
        _lblHealthBlocks.Text = $"{_config.TotalBlockedCount} blocked attempts | {_config.CustomKeywords.Count} custom keywords | {_config.CustomWebsites.Count} custom sites";
    }

    public void ReloadConfigFromDisk()
    {
        if (InvokeRequired) { BeginInvoke(ReloadConfigFromDisk); return; }
        _config = AppConfig.Load();
        BindConfigToUi();
        _scanner?.SyncConfig();
    }

    public void HandleRemoteIntimateCaption(string caption)
    {
        if (InvokeRequired) { BeginInvoke(() => HandleRemoteIntimateCaption(caption)); return; }
        if (!_config.ProtectionEnabled || !_config.IntimateGuardEnabled) return;
        _exitController.TriggerIntimateAction(new IntimateSignals(true, true, $"Intimate Caption ({caption})", 0.85f, false));
    }

    private void ToggleMasterProtection()
    {
        if (_config.ProtectionEnabled &&
            !VerificationDialogForm.VerifyProtectionChange($"Pause {BrandConfig.AppName} Protection", _config.PasswordHash, this))
        {
            return;
        }
        _config.ProtectionEnabled = !_config.ProtectionEnabled;
        SaveAndSync();
        UpdateMasterButtons();
    }

    private void TriggerManualLockout()
    {
        _config.LockoutUntilMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + ContentGuardRules.LockoutDurationMs;
        _config.Save();
    }

    private void SaveAiSettings()
    {
        if (_isBindingUi) return;
        _config.GenderFilter = _rbGenderFemale.Checked ? GenderFilter.FemaleOnly
            : _rbGenderMale.Checked ? GenderFilter.MaleOnly : GenderFilter.Both;
        _config.BlurFaces = _chkBlurFaces.Checked;
        _config.BlurStyle = _rbStyleFrosted.Checked ? BlurStyle.DarkFrosted
            : _rbStyleLight.Checked ? BlurStyle.LightCensor : BlurStyle.SolidBlack;
        _config.BlurOpacity = _trkOpacity.Value;
        _config.ScanIntervalMs = _trkInterval.Value;
        SaveAndSync();
    }

    private void OnSensitivityCommitted()
    {
        if (_isBindingUi) return;
        float target = _trkSensitivity.Value / 100f;
        if (target > _config.Sensitivity && !string.IsNullOrWhiteSpace(_config.PasswordHash))
        {
            if (!VerificationDialogForm.VerifyProtectionChange("Loosen Detection Sensitivity", _config.PasswordHash, this))
            {
                _isBindingUi = true;
                _trkSensitivity.Value = Math.Clamp((int)Math.Round(_config.Sensitivity * 100f), 15, 45);
                _lblSensitivityVal.Text = $"Sensitivity Threshold: {_config.Sensitivity:F2}";
                _isBindingUi = false;
                return;
            }
        }
        _config.Sensitivity = target;
        SaveAndSync();
    }

    private void SaveIntimateSettings()
    {
        if (_isBindingUi) return;
        _config.IntimateGuardEnabled = _chkIntimateGuard.Checked;
        _config.IntimateAudioEnabled = _chkIntimateAudio.Checked;
        _config.AutoMovieAudioEnabled = _chkAutoMovieAudio.Checked;
        _config.CommunitySkipEnabled = _chkCommunitySkip.Checked;
        _config.IntimateActionMode = _rbActionBack.Checked ? IntimateActionMode.BackOnly : IntimateActionMode.SmartContext;
        SaveAndSync();
    }

    private void ShowAddCustomTimestampDialog()
    {
        using var dlg = new Form
        {
            Text = "Add Custom / MCF Skip Timestamps",
            Size = new Size(520, 320),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            BackColor = BgSlate,
            ForeColor = Color.White
        };

        dlg.Controls.Add(new Label { Text = "Movie or Episode Title (e.g. Oppenheimer or The Body S01E06):", Location = new Point(16, 14), Size = new Size(470, 20) });
        var txtTitle = new TextBox { Location = new Point(16, 38), Size = new Size(470, 26), BackColor = CardSlate, ForeColor = Color.White };
        dlg.Controls.Add(txtTitle);

        dlg.Controls.Add(new Label { Text = "Timestamps (e.g. 35:30-37:10, 01:14:48 -> 01:15:13 or CleanStream JSON):", Location = new Point(16, 74), Size = new Size(470, 20) });
        var txtRanges = new TextBox { Location = new Point(16, 98), Size = new Size(470, 110), Multiline = true, ScrollBars = ScrollBars.Vertical, BackColor = CardSlate, ForeColor = Color.White };
        dlg.Controls.Add(txtRanges);

        var btnSave = MakeButton("Save Timestamps", Color.FromArgb(109, 40, 217), new Size(150, 32));
        btnSave.Location = new Point(336, 224);
        btnSave.Click += (_, _) =>
        {
            var segs = CommunitySkipRules.ParseCustomTimestampsInput(txtRanges.Text);
            if (!_config.SaveCustomSkipEntry(txtTitle.Text, segs))
            {
                MessageBox.Show("Enter a valid title and at least one time range (e.g. 35:30-37:10).", BrandConfig.DesktopDisplayName, MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            SaveAndSync();
            dlg.DialogResult = DialogResult.OK;
            dlg.Close();
        };
        dlg.Controls.Add(btnSave);

        dlg.ShowDialog(this);
    }

    private void RemoveSelectedCustomSkip()
    {
        int idx = _lstCustomSkips.SelectedIndex;
        if (idx < 0 || idx >= _config.CustomSkipEntries.Count) return;
        var entry = _config.CustomSkipEntries[idx];
        if (!VerificationDialogForm.VerifyProtectionChange($"Remove custom timestamps for '{entry.DisplayTitle}'", _config.PasswordHash, this))
        {
            return;
        }
        _config.RemoveCustomSkipEntry(entry.Key);
        SaveAndSync();
    }

    private void SaveDrmSetting(DrmAppPolicy policy)
    {
        if (_isBindingUi) return;
        if (_config.DrmAppPolicy == DrmAppPolicy.AutoBlock && policy != DrmAppPolicy.AutoBlock &&
            !VerificationDialogForm.VerifyProtectionChange("Relax DRM App Policy", _config.PasswordHash, this))
        {
            BindConfigToUi();
            return;
        }
        _config.DrmAppPolicy = policy;
        SaveAndSync();
    }

    private void SaveAndSync()
    {
        _config.Save();
        _scanner?.SyncConfig();
        RefreshListBoxes();
        RefreshHealthLabels();
    }

    private void QuickAddWebsite()
    {
        string site = ContentGuardRules.NormalizeHost(_txtQuickSite.Text);
        if (string.IsNullOrEmpty(site) || !site.Contains('.'))
        {
            _lblQuickFeedback.ForeColor = Color.FromArgb(244, 63, 94);
            _lblQuickFeedback.Text = "Enter a valid domain (e.g. example.com).";
            return;
        }
        if (!_config.CustomWebsites.Contains(site, StringComparer.OrdinalIgnoreCase))
        {
            _config.CustomWebsites.Add(site);
            SaveAndSync();
            _txtQuickSite.Clear();
            _lblQuickFeedback.ForeColor = Color.FromArgb(74, 222, 128);
            _lblQuickFeedback.Text = $"Blocked website added: {site}";
        }
    }

    private void QuickAddKeyword()
    {
        string kw = _txtQuickKw.Text.Trim().ToLowerInvariant();
        if (string.IsNullOrEmpty(kw))
        {
            _lblQuickFeedback.ForeColor = Color.FromArgb(244, 63, 94);
            _lblQuickFeedback.Text = "Enter a keyword to block.";
            return;
        }
        if (!_config.CustomKeywords.Contains(kw, StringComparer.OrdinalIgnoreCase))
        {
            _config.CustomKeywords.Add(kw);
            SaveAndSync();
            _txtQuickKw.Clear();
            _lblQuickFeedback.ForeColor = Color.FromArgb(74, 222, 128);
            _lblQuickFeedback.Text = $"Blocked keyword added: '{kw}' (enforced in URLs, titles & live search bars)";
        }
    }

    private void UpdateMasterPassword()
    {
        string cur = _txtCurPw.Text;
        string next = _txtNewPw.Text;
        if (string.IsNullOrWhiteSpace(next))
        {
            _lblPwFeedback.ForeColor = Color.FromArgb(244, 63, 94);
            _lblPwFeedback.Text = "New password cannot be empty.";
            return;
        }
        if (!string.IsNullOrWhiteSpace(_config.PasswordHash) && !ContentGuardRules.VerifyPassword(cur, _config.PasswordHash))
        {
            _lblPwFeedback.ForeColor = Color.FromArgb(244, 63, 94);
            _lblPwFeedback.Text = "Current password is incorrect.";
            return;
        }
        _config.PasswordHash = ContentGuardRules.HashPassword(next);
        _config.Save();
        _txtCurPw.Clear();
        _txtNewPw.Clear();
        _lblPwFeedback.ForeColor = Color.FromArgb(74, 222, 128);
        _lblPwFeedback.Text = "Master password updated successfully.";
    }

    private async Task CheckUpdatesAsync(bool manual)
    {
        if (manual)
        {
            _btnCheckUpdates.Enabled = false;
            _lblUpdateStatus.Text = "Checking GitHub Releases (pc-v*)...";
        }

        var info = await UpdateChecker.CheckLatestAsync();
        if (manual) _btnCheckUpdates.Enabled = true;

        if (info == null)
        {
            if (manual) _lblUpdateStatus.Text = $"No newer PC release found (current v{BrandConfig.AppVersion}).";
            return;
        }

        bool isNewer = UpdateChecker.IsNewerVersion(info.VersionName, BrandConfig.AppVersion, info.VersionCode, BrandConfig.AppVersionCode);
        if (!isNewer)
        {
            if (manual) _lblUpdateStatus.Text = $"{BrandConfig.DesktopDisplayName} is up to date (v{BrandConfig.AppVersion}).";
            return;
        }

        if (!manual)
        {
            long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (_config.RemindMeLaterUntilMs > nowMs ||
                string.Equals(_config.DismissedUpdateVersion, info.VersionName, StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
        }

        _lblUpdateStatus.Text = $"Update available: v{info.VersionName}";
        using var dlg = new UpdateDialogForm(info, _config);
        dlg.ShowDialog(this);
    }

    private void PromptUninstall()
    {
        if (!VerificationDialogForm.VerifyProtectionChange($"Uninstall {BrandConfig.AppName}", _config.PasswordHash, this))
        {
            return;
        }
        var (_, log) = InstallerEngine.Uninstall(AppContext.BaseDirectory);
        MessageBox.Show($"{BrandConfig.DesktopDisplayName} protections uninstalled:\n\n{log}",
            BrandConfig.DesktopDisplayName, MessageBoxButtons.OK, MessageBoxIcon.Information);
        _allowExit = true;
        Application.Exit();
    }

    private static void OpenExtensionFolder()
    {
        string[] candidates =
        [
            Path.Combine(AppContext.BaseDirectory, "extension"),
            Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "..", "..", "..", "..", "..", "extension")),
            @"K:\SaveGaze-PC\extension"
        ];
        string? found = candidates.FirstOrDefault(Directory.Exists);
        if (found != null)
        {
            Process.Start(new ProcessStartInfo { FileName = "explorer.exe", Arguments = $"\"{found}\"", UseShellExecute = true });
        }
    }

    private void OnMainFormClosing(object? sender, FormClosingEventArgs e)
    {
        if (!_allowExit && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            ShowInTaskbar = false;
            return;
        }

        _uiRefreshTimer.Stop();
        _uiRefreshTimer.Dispose();
        _guardService.Dispose();
        _scanner?.Dispose();
        _audioMonitor?.Dispose();
        _audioDetector?.Dispose();
        _clipDetector?.Dispose();
        _sceneDetector?.Dispose();
        _genderDetector?.Dispose();
        _nudeDetector?.Dispose();
        _overlay.Dispose();
        _trayIcon.Visible = false;
        _trayIcon.Dispose();
    }

    // ── Small UI builder helpers ───────────────────────────────────────

    private static Panel MakeCard(int x, int y, int w, int h) =>
        new() { Location = new Point(x, y), Size = new Size(w, h), BackColor = CardSlate };

    private static Label MakeTitle(string text, int x, int y) =>
        new() { Text = text, Location = new Point(x, y), AutoSize = true, Font = new Font("Segoe UI", 10.5f, FontStyle.Bold), ForeColor = Color.FromArgb(196, 181, 253) };

    private static RadioButton MakeRadio(Control parent, string text, int x, int y)
    {
        var rb = new RadioButton { Text = text, Location = new Point(x, y), AutoSize = true, ForeColor = Color.White };
        parent.Controls.Add(rb);
        return rb;
    }

    private static CheckBox MakeCheck(Control parent, string text, int x, int y)
    {
        var chk = new CheckBox { Text = text, Location = new Point(x, y), Size = new Size(840, 26), ForeColor = Color.White };
        parent.Controls.Add(chk);
        return chk;
    }

    private CheckBox MakeProtectedToggle(Panel parent, string text, int x, int y, string challengeTitle, Action<bool> setter)
    {
        var chk = new CheckBox { Text = text, Location = new Point(x, y), Size = new Size(410, 26), ForeColor = Color.White, Font = new Font("Segoe UI", 9.5f, FontStyle.Bold) };
        chk.CheckedChanged += (_, _) =>
        {
            if (_isBindingUi) return;
            if (!chk.Checked && !VerificationDialogForm.VerifyProtectionChange(challengeTitle, _config.PasswordHash, this))
            {
                _isBindingUi = true;
                chk.Checked = true;
                _isBindingUi = false;
                return;
            }
            setter(chk.Checked);
            SaveAndSync();
        };
        parent.Controls.Add(chk);
        return chk;
    }

    private (TextBox Input, ListBox List) BuildListCard(
        TableLayoutPanel parent, int col, string title, Action<string> onAdd, Action<string> onRemove)
    {
        var card = new Panel { Dock = DockStyle.Fill, BackColor = CardSlate, Padding = new Padding(10), Margin = new Padding(5) };
        card.Controls.Add(new Label { Text = title, Dock = DockStyle.Top, Height = 26, Font = new Font("Segoe UI", 10f, FontStyle.Bold) });

        var inputRow = new Panel { Dock = DockStyle.Top, Height = 32 };
        var txt = new TextBox { Dock = DockStyle.Fill, BackColor = BgSlate, ForeColor = Color.White };
        var btnAdd = MakeButton("Add", Color.FromArgb(109, 40, 217), new Size(56, 30));
        btnAdd.Dock = DockStyle.Right;
        btnAdd.Click += (_, _) => { if (!string.IsNullOrWhiteSpace(txt.Text)) { onAdd(txt.Text.Trim()); txt.Clear(); } };
        txt.KeyDown += (_, e) => { if (e.KeyCode == Keys.Enter) { e.SuppressKeyPress = true; btnAdd.PerformClick(); } };
        inputRow.Controls.Add(txt);
        inputRow.Controls.Add(btnAdd);
        card.Controls.Add(inputRow);

        var lst = new ListBox { Dock = DockStyle.Fill, BackColor = BgSlate, ForeColor = Color.White, BorderStyle = BorderStyle.None };
        var btnDel = MakeButton("Remove (Challenge Required)", Color.FromArgb(51, 65, 85), new Size(100, 30));
        btnDel.Dock = DockStyle.Bottom;
        btnDel.Click += (_, _) =>
        {
            if (lst.SelectedItem is string item &&
                VerificationDialogForm.VerifyProtectionChange($"Remove '{item}'", _config.PasswordHash, this))
            {
                onRemove(item);
            }
        };
        card.Controls.Add(btnDel);
        card.Controls.Add(lst);
        lst.BringToFront();
        parent.Controls.Add(card, col, 0);
        return (txt, lst);
    }

    private static Label CreateHealthRow(Panel parent, int y, string label, string initialValue)
    {
        parent.Controls.Add(new Label { Text = label, Location = new Point(14, y), Size = new Size(210, 22), ForeColor = Color.FromArgb(148, 163, 184) });
        var val = new Label { Text = initialValue, Location = new Point(230, y), Size = new Size(640, 22), Font = new Font("Segoe UI", 9.5f, FontStyle.Bold), ForeColor = Color.FromArgb(74, 222, 128) };
        parent.Controls.Add(val);
        return val;
    }

    private static Button MakeButton(string text, Color bg, Size size)
    {
        var b = new Button
        {
            Text = text,
            BackColor = bg,
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            Size = size
        };
        b.FlatAppearance.BorderSize = 0;
        return b;
    }
}
