using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using SaveGaze.Core;

namespace SaveGaze.Desktop;

public sealed class NoPasteTextBox : TextBox
{
    private const int WM_PASTE = 0x0302;
    private const int WM_CONTEXTMENU = 0x007B;

    public NoPasteTextBox()
    {
        ShortcutsEnabled = false;
        ContextMenuStrip = new ContextMenuStrip();
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WM_PASTE || m.Msg == WM_CONTEXTMENU) return;
        base.WndProc(ref m);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if ((e.Control && e.KeyCode == Keys.V) || (e.Shift && e.KeyCode == Keys.Insert))
        {
            e.SuppressKeyPress = true;
            return;
        }
        base.OnKeyDown(e);
    }
}

public sealed class SentenceChallengeForm : Form
{
    private readonly string _targetSentence;
    private int _remainingSeconds = ContentGuardRules.ChallengeSeconds;
    private readonly System.Windows.Forms.Timer _countdownTimer;

    private readonly PictureBox _picSentence;
    private readonly NoPasteTextBox _txtInput;
    private readonly Label _lblTimer;
    private readonly Label _lblStatus;
    private readonly Button _btnSubmit;
    private readonly ProgressBar _prgTime;

    public SentenceChallengeForm(string actionTitle)
    {
        var list = ContentGuardLists.CommitmentSentences;
        _targetSentence = list[Random.Shared.Next(list.Count)];

        Text = $"{BrandConfig.AppName} — {actionTitle} Commitment Challenge";
        Size = new Size(680, 480);
        MinimumSize = new Size(680, 480);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        TopMost = true;
        BackColor = Color.FromArgb(15, 23, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);
        TryLoadAppIcon();

        Controls.Add(new Label
        {
            Text = "STRICT DEACTIVATION & UNINSTALL CHALLENGE",
            Font = new Font("Segoe UI", 11.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(244, 63, 94),
            Location = new Point(25, 18),
            AutoSize = true
        });

        Controls.Add(new Label
        {
            Text = $"To authorize {actionTitle.ToLowerInvariant()}, accurately type the commitment sentence below before time expires. Copy-pasting is strictly blocked.",
            Font = new Font("Segoe UI", 9f),
            ForeColor = Color.FromArgb(203, 213, 225),
            Location = new Point(26, 45),
            Size = new Size(615, 38)
        });

        _lblTimer = new Label
        {
            Text = $"Time Remaining: 0:{_remainingSeconds:D2}",
            Font = new Font("Segoe UI", 12f, FontStyle.Bold),
            ForeColor = Color.FromArgb(250, 204, 21),
            Location = new Point(26, 88),
            AutoSize = true
        };
        Controls.Add(_lblTimer);

        _prgTime = new ProgressBar
        {
            Location = new Point(240, 93),
            Size = new Size(398, 16),
            Maximum = ContentGuardRules.ChallengeSeconds,
            Value = ContentGuardRules.ChallengeSeconds
        };
        Controls.Add(_prgTime);

        _picSentence = new PictureBox
        {
            Location = new Point(26, 122),
            Size = new Size(612, 110),
            BorderStyle = BorderStyle.FixedSingle,
            BackColor = Color.FromArgb(30, 41, 59)
        };
        _picSentence.Paint += OnSentenceCanvasPaint;
        Controls.Add(_picSentence);

        Controls.Add(new Label
        {
            Text = "Type the commitment sentence exactly as displayed above:",
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(26, 242),
            AutoSize = true
        });

        _txtInput = new NoPasteTextBox
        {
            Location = new Point(26, 266),
            Size = new Size(612, 80),
            Multiline = true,
            Font = new Font("Segoe UI", 10.5f),
            BackColor = Color.FromArgb(15, 23, 42),
            ForeColor = Color.White,
            BorderStyle = BorderStyle.FixedSingle
        };
        _txtInput.TextChanged += OnInputTextChanged;
        Controls.Add(_txtInput);

        _lblStatus = new Label
        {
            Text = $"0 / {_targetSentence.Length} characters matched",
            Font = new Font("Segoe UI", 9f),
            ForeColor = Color.FromArgb(148, 163, 184),
            Location = new Point(26, 354),
            AutoSize = true
        };
        Controls.Add(_lblStatus);

        _btnSubmit = new Button
        {
            Text = "Verify & Proceed ->",
            Font = new Font("Segoe UI", 10f, FontStyle.Bold),
            BackColor = Color.FromArgb(225, 29, 72),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(478, 385),
            Size = new Size(160, 38),
            Enabled = false
        };
        _btnSubmit.FlatAppearance.BorderSize = 0;
        _btnSubmit.Click += OnSubmitClick;
        Controls.Add(_btnSubmit);

        var btnCancel = new Button
        {
            Text = "Cancel",
            Font = new Font("Segoe UI", 9.5f),
            BackColor = Color.FromArgb(51, 65, 85),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(370, 385),
            Size = new Size(95, 38)
        };
        btnCancel.FlatAppearance.BorderSize = 0;
        btnCancel.Click += (_, _) =>
        {
            DialogResult = DialogResult.Cancel;
            Close();
        };
        Controls.Add(btnCancel);

        _countdownTimer = new System.Windows.Forms.Timer { Interval = 1000 };
        _countdownTimer.Tick += OnCountdownTick;
        _countdownTimer.Start();
    }

    private void TryLoadAppIcon()
    {
        string ico = Path.Combine(AppContext.BaseDirectory, "assets", "SaveGaze.ico");
        if (File.Exists(ico))
        {
            try { Icon = new Icon(ico); } catch { }
        }
    }

    private void OnSentenceCanvasPaint(object? sender, PaintEventArgs e)
    {
        var g = e.Graphics;
        g.SmoothingMode = SmoothingMode.AntiAlias;
        g.TextRenderingHint = TextRenderingHint.AntiAliasGridFit;

        using var gridPen = new Pen(Color.FromArgb(40, 50, 70), 1);
        for (int x = 0; x < _picSentence.Width; x += 25) g.DrawLine(gridPen, x, 0, x, _picSentence.Height);
        for (int y = 0; y < _picSentence.Height; y += 25) g.DrawLine(gridPen, 0, y, _picSentence.Width, y);

        using var font = new Font("Segoe UI", 11.5f, FontStyle.Bold);
        using var brush = new SolidBrush(Color.FromArgb(248, 250, 252));
        using var sf = new StringFormat { Alignment = StringAlignment.Center, LineAlignment = StringAlignment.Center };
        var rect = new Rectangle(12, 10, _picSentence.Width - 24, _picSentence.Height - 20);
        g.DrawString(_targetSentence, font, brush, rect, sf);
    }

    private void OnCountdownTick(object? sender, EventArgs e)
    {
        _remainingSeconds--;
        if (_remainingSeconds <= 0)
        {
            _countdownTimer.Stop();
            _lblTimer.Text = "Time Expired!";
            _lblTimer.ForeColor = Color.FromArgb(239, 68, 68);
            _prgTime.Value = 0;
            _txtInput.Enabled = false;
            _btnSubmit.Enabled = false;
            MessageBox.Show(
                $"Time expired! Action blocked by {BrandConfig.AppName}.",
                "Time Expired", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            DialogResult = DialogResult.Cancel;
            Close();
            return;
        }

        _lblTimer.Text = $"Time Remaining: 0:{_remainingSeconds:D2}";
        _prgTime.Value = Math.Max(0, _remainingSeconds);
        if (_remainingSeconds <= 10) _lblTimer.ForeColor = Color.FromArgb(239, 68, 68);
    }

    private void OnInputTextChanged(object? sender, EventArgs e)
    {
        string current = _txtInput.Text;
        int matchCount = 0;
        int len = Math.Min(current.Length, _targetSentence.Length);
        for (int i = 0; i < len; i++)
        {
            if (current[i] == _targetSentence[i]) matchCount++;
            else break;
        }

        if (matchCount == _targetSentence.Length && current.Length == _targetSentence.Length)
        {
            _lblStatus.ForeColor = Color.FromArgb(74, 222, 128);
            _lblStatus.Text = "Sentence matched perfectly!";
            _btnSubmit.Enabled = true;
        }
        else
        {
            _lblStatus.ForeColor = matchCount == current.Length ? Color.FromArgb(148, 163, 184) : Color.FromArgb(244, 63, 94);
            _lblStatus.Text = $"{matchCount} / {_targetSentence.Length} characters matched";
            _btnSubmit.Enabled = false;
        }
    }

    private void OnSubmitClick(object? sender, EventArgs e)
    {
        if (_txtInput.Text.Trim() == _targetSentence.Trim())
        {
            _countdownTimer.Stop();
            DialogResult = DialogResult.OK;
            Close();
        }
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        _countdownTimer.Stop();
        _countdownTimer.Dispose();
        base.OnFormClosing(e);
    }

    public static bool ChallengeUser(string actionTitle, IWin32Window? owner = null)
    {
        using var form = new SentenceChallengeForm(actionTitle);
        return (owner != null ? form.ShowDialog(owner) : form.ShowDialog()) == DialogResult.OK;
    }
}

public sealed class VerificationDialogForm : Form
{
    private readonly string _storedHash;
    private readonly string _actionTitle;
    private readonly bool _requireSentence;
    private readonly TextBox _txtPassword;
    private readonly Label _lblError;
    public bool VerificationPassed { get; private set; }

    public VerificationDialogForm(string actionTitle, string storedHash, bool requireSentence = true)
    {
        _actionTitle = actionTitle;
        _storedHash = storedHash;
        _requireSentence = requireSentence;

        Text = $"{BrandConfig.AppName} — {actionTitle} Defense";
        Size = new Size(470, 290);
        StartPosition = FormStartPosition.CenterScreen;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        TopMost = true;
        BackColor = Color.FromArgb(15, 23, 42);
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        Controls.Add(new Label
        {
            Text = $"Defense Shield: {actionTitle}",
            Font = new Font("Segoe UI", 11.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(244, 63, 94),
            Location = new Point(22, 18),
            Size = new Size(420, 28)
        });

        Controls.Add(new Label
        {
            Text = $"Authorizing '{actionTitle}' requires verifying your Master Password" +
                   (requireSentence ? " and passing the 40-second Commitment Challenge." : "."),
            Font = new Font("Segoe UI", 9f),
            ForeColor = Color.FromArgb(203, 213, 225),
            Location = new Point(24, 48),
            Size = new Size(410, 42)
        });

        Controls.Add(new Label
        {
            Text = "Master Password:",
            Font = new Font("Segoe UI", 9f, FontStyle.Bold),
            Location = new Point(24, 98),
            Size = new Size(200, 20)
        });

        _txtPassword = new TextBox
        {
            Font = new Font("Segoe UI", 11f),
            PasswordChar = '*',
            Location = new Point(25, 122),
            Size = new Size(405, 28)
        };
        Controls.Add(_txtPassword);

        _lblError = new Label
        {
            Font = new Font("Segoe UI", 8.5f, FontStyle.Bold),
            ForeColor = Color.FromArgb(244, 63, 94),
            Location = new Point(25, 155),
            Size = new Size(405, 25)
        };
        Controls.Add(_lblError);

        var btnProceed = new Button
        {
            Text = requireSentence ? "Start Sentence Challenge ->" : "Verify & Unlock",
            Font = new Font("Segoe UI", 9.5f, FontStyle.Bold),
            BackColor = Color.FromArgb(225, 29, 72),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(205, 195),
            Size = new Size(225, 36)
        };
        btnProceed.FlatAppearance.BorderSize = 0;
        btnProceed.Click += (_, _) => OnProceed();
        Controls.Add(btnProceed);

        var btnCancel = new Button
        {
            Text = "Cancel",
            Font = new Font("Segoe UI", 9f),
            BackColor = Color.FromArgb(51, 65, 85),
            ForeColor = Color.White,
            FlatStyle = FlatStyle.Flat,
            Location = new Point(105, 195),
            Size = new Size(90, 36)
        };
        btnCancel.FlatAppearance.BorderSize = 0;
        btnCancel.Click += (_, _) => { DialogResult = DialogResult.Cancel; Close(); };
        Controls.Add(btnCancel);

        _txtPassword.KeyDown += (_, e) =>
        {
            if (e.KeyCode == Keys.Enter)
            {
                e.SuppressKeyPress = true;
                OnProceed();
            }
        };
    }

    private void OnProceed()
    {
        if (!string.IsNullOrWhiteSpace(_storedHash))
        {
            string pw = _txtPassword.Text;
            if (string.IsNullOrEmpty(pw))
            {
                _lblError.Text = "Master password is required.";
                return;
            }
            if (!ContentGuardRules.VerifyPassword(pw, _storedHash))
            {
                _lblError.Text = "Incorrect master password.";
                _txtPassword.SelectAll();
                _txtPassword.Focus();
                return;
            }
        }

        if (_requireSentence && !SentenceChallengeForm.ChallengeUser(_actionTitle, this))
        {
            _lblError.Text = "Sentence challenge not completed.";
            return;
        }

        VerificationPassed = true;
        DialogResult = DialogResult.OK;
        Close();
    }

    public static bool VerifyProtectionChange(string actionTitle, string storedPasswordHash, IWin32Window? owner = null, bool requireSentence = true)
    {
        // Fast path: if no password is set and sentence challenge is required, go straight to sentence challenge
        if (string.IsNullOrWhiteSpace(storedPasswordHash) && requireSentence)
        {
            return SentenceChallengeForm.ChallengeUser(actionTitle, owner);
        }
        using var dlg = new VerificationDialogForm(actionTitle, storedPasswordHash, requireSentence);
        return (owner != null ? dlg.ShowDialog(owner) : dlg.ShowDialog()) == DialogResult.OK && dlg.VerificationPassed;
    }
}

public sealed class LockoutOverlayForm : Form
{
    private long _untilMs;
    private readonly string _storedPasswordHash;
    private readonly Action _onUnlockedEarly;
    private readonly Action _onExpired;
    private readonly System.Windows.Forms.Timer _timer;
    private readonly Label _lblTimer;
    private bool _allowClose;

    public LockoutOverlayForm(long untilMs, string storedPasswordHash, Action onUnlockedEarly, Action onExpired)
    {
        _untilMs = untilMs;
        _storedPasswordHash = storedPasswordHash;
        _onUnlockedEarly = onUnlockedEarly;
        _onExpired = onExpired;

        var vs = SystemInformation.VirtualScreen;
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.Manual;
        Bounds = vs;
        TopMost = true;
        BackColor = Color.FromArgb(15, 23, 42);
        ForeColor = Color.White;
        ShowInTaskbar = false;

        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            RowCount = 5,
            ColumnCount = 1,
            BackColor = Color.FromArgb(15, 23, 42)
        };
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 25));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 35));

        panel.Controls.Add(new Label
        {
            Text = "LOCKDOWN ACTIVE",
            Font = new Font("Segoe UI", 26f, FontStyle.Bold),
            ForeColor = Color.FromArgb(244, 63, 94),
            AutoSize = true,
            Anchor = AnchorStyles.None
        }, 0, 1);

        panel.Controls.Add(new Label
        {
            Text = $"Multiple blocked attempts detected within 10 minutes.\n{BrandConfig.AppName} initiated a 5-minute cooldown lockout.",
            Font = new Font("Segoe UI", 12.5f),
            ForeColor = Color.FromArgb(203, 213, 225),
            TextAlign = ContentAlignment.MiddleCenter,
            AutoSize = true,
            Padding = new Padding(0, 14, 0, 18),
            Anchor = AnchorStyles.None
        }, 0, 2);

        _lblTimer = new Label
        {
            Text = "05:00",
            Font = new Font("Segoe UI", 48f, FontStyle.Bold),
            ForeColor = Color.FromArgb(250, 204, 21),
            AutoSize = true,
            Anchor = AnchorStyles.None
        };
        panel.Controls.Add(_lblTimer, 0, 3);

        if (!string.IsNullOrWhiteSpace(_storedPasswordHash))
        {
            var btnUnlock = new Button
            {
                Text = "Unlock Early with Master Password",
                Font = new Font("Segoe UI", 10f, FontStyle.Bold),
                BackColor = Color.FromArgb(225, 29, 72),
                ForeColor = Color.White,
                FlatStyle = FlatStyle.Flat,
                Size = new Size(280, 40),
                Anchor = AnchorStyles.Top
            };
            btnUnlock.FlatAppearance.BorderSize = 0;
            btnUnlock.Click += (_, _) =>
            {
                if (VerificationDialogForm.VerifyProtectionChange("Unlock Auto-Lockout", _storedPasswordHash, this, requireSentence: true))
                {
                    _allowClose = true;
                    _onUnlockedEarly();
                    Close();
                }
            };
            panel.Controls.Add(btnUnlock, 0, 4);
        }

        Controls.Add(panel);

        _timer = new System.Windows.Forms.Timer { Interval = 500 };
        _timer.Tick += (_, _) => UpdateCountdown();
        _timer.Start();
        UpdateCountdown();

        FormClosing += (_, e) =>
        {
            if (!_allowClose && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() < _untilMs)
            {
                e.Cancel = true;
            }
            else
            {
                _timer.Stop();
                _timer.Dispose();
            }
        };
    }

    public void UpdateUntil(long untilMs) => _untilMs = untilMs;
    public void AllowClose() => _allowClose = true;

    private void UpdateCountdown()
    {
        long remMs = _untilMs - DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (remMs <= 0)
        {
            _allowClose = true;
            _timer.Stop();
            _onExpired();
            Close();
            return;
        }
        int totalSec = (int)(remMs / 1000);
        _lblTimer.Text = $"{totalSec / 60:D2}:{totalSec % 60:D2}";
    }
}
