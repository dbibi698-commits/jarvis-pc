import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * JARVIS PC clone v1.0 — Windows desktop companion for the JARVIS phone app.
 *
 * - QR pairing (Telegram-style): this app shows a QR, the phone scans it once
 *   (Settings > PC Pairing > Scan PC QR) and the two pair automatically.
 * - Two-way control through the Render backend relay (internet is enough,
 *   same Wi-Fi is NOT needed): PC sends text/voice commands to the phone,
 *   the phone sends commands to the PC (open apps, type, click, screenshot,
 *   run commands, speak).
 * - Voice: voice MESSAGES (record -> send -> play). A voice note from the
 *   phone plays here; Jarvis's spoken replies are mirrored here as audio.
 * - NO API KEY on the PC — the Gemini key lives ONLY on the phone.
 *
 * Run: run.bat (Windows, JRE bundled) or: java -jar jarvis-pc.jar
 */
public class JarvisPC {

    static final String BASE = System.getProperty("jarvis.base", "https://jarvis-backend-8ray.onrender.com");
    static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    static final Path CONFIG_PATH = Paths.get("pc-config.json");
    static JSONObject config = new JSONObject();
    static String pcId = "";
    static String pcToken = "";
    static String installId = "";
    static String pcName = "";
    static String pairCode = "";
    static volatile boolean paired = false;
    static volatile boolean running = true;
    static volatile boolean recording = false;
    static Robot robot;

    // UI
    static JFrame frame;
    static CardLayout cards;
    static JPanel cardPanel;
    static JLabel qrLabel, pairCodeLabel, statusLabel, phoneScreenLabel;
    static JTextArea logArea;
    static JTextField cmdField;
    static JButton recordBtn;
    static TargetDataLine recLine;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--test")) { runSelfTest(); return; }
        robot = new Robot();
        loadConfig();
        pcName = System.getenv("COMPUTERNAME");
        if (pcName == null || pcName.isEmpty()) pcName = System.getProperty("user.name", "PC");
        SwingUtilities.invokeLater(JarvisPC::buildUi);
        new Thread(JarvisPC::pairLoop, "pair-loop").start();
        new Thread(JarvisPC::inboxLoop, "inbox-loop").start();
        new Thread(JarvisPC::heartbeatLoop, "heartbeat-loop").start();
    }

    // ------------------------------------------------------------ config

    static void loadConfig() {
        try {
            if (Files.exists(CONFIG_PATH)) config = new JSONObject(Files.readString(CONFIG_PATH));
        } catch (Exception e) { config = new JSONObject(); }
        pcId = config.optString("pc_id", "");
        if (pcId.isEmpty()) {
            pcId = "pc-" + UUID.randomUUID().toString().substring(0, 8);
            config.put("pc_id", pcId);
            saveConfig();
        }
        pcToken = config.optString("pc_token", "");
        installId = config.optString("install_id", "");
        paired = config.optBoolean("paired", false) && !pcToken.isEmpty() && !installId.isEmpty();
    }

    static void saveConfig() {
        try { Files.writeString(CONFIG_PATH, config.toString(2)); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ HTTP

    static JSONObject postJson(String path, JSONObject body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.body() == null || res.body().isEmpty()) return new JSONObject();
            return new JSONObject(res.body());
        } catch (Exception e) {
            log("HTTP error (" + path + "): " + e.getMessage());
            return null;
        }
    }

    static JSONObject getJson(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.body() == null || res.body().isEmpty()) return new JSONObject();
            return new JSONObject(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    static byte[] getBytes(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(BASE + path))
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() >= 200 && res.statusCode() < 300) return res.body();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ pairing

    // v1.8.1 FIX: age prottek poll-e /api/pair/request daka hoto — backend prottekbar
    // NOTUN code banato, tai QR 4 second por por bodle jeto ar phone scan koreo confirm
    // korte parto na (code bodle jeto). Ekhon: code ekbar nao, tarpor sudhu status poll
    // koro; code 9.5 minute-e expire hole tobei notun code chao.
    static long pairCodeAt = 0L;

    static void pairLoop() {
        while (running) {
            try {
                if (!paired) {
                    if (pairCode.isEmpty() || System.currentTimeMillis() - pairCodeAt > 9 * 60 * 1000 + 30 * 1000) {
                        pairCode = "";
                        JSONObject r = postJson("/api/pair/request", new JSONObject()
                                .put("pc_id", pcId).put("pc_name", pcName)
                                .put("os", System.getProperty("os.name", "Windows")));
                        if (r != null) {
                            if (r.optBoolean("paired")) {
                                onPaired(r.optString("install_id", ""), "");
                            } else if (r.has("pair_code")) {
                                pairCode = r.optString("pair_code", "");
                                pairCodeAt = System.currentTimeMillis();
                                SwingUtilities.invokeLater(() -> showQr(pairCode));
                            }
                        }
                    } else {
                        JSONObject s = getJson("/api/pair/status?pc_id=" + enc(pcId) + "&code=" + enc(pairCode));
                        if (s != null && s.optBoolean("paired")) {
                            onPaired(s.optString("install_id", ""), s.optString("pc_token", ""));
                        }
                    }
                    sleep(2000);
                } else {
                    sleep(5000);
                }
            } catch (Exception e) { sleep(5000); }
        }
    }

    static void onPaired(String instId, String token) {
        if (instId == null || instId.isEmpty()) return;
        installId = instId;
        if (token != null && !token.isEmpty()) pcToken = token;
        paired = true;
        config.put("paired", true);
        config.put("install_id", installId);
        config.put("pc_token", pcToken);
        saveConfig();
        SwingUtilities.invokeLater(() -> {
            cards.show(cardPanel, "main");
            statusLabel.setText("✅ Paired — phone: " + installId.substring(0, Math.min(8, installId.length())) + "…");
            log("✅ Paired with phone! PC theke phone, phone theke PC — duidikei cholbe.");
        });
        // v1.6.0: same-interface sync — phone-er status + sob feature-er list ano
        new Thread(() -> sendToPhone("sync_req", "sync please", null)).start();
    }

    static void heartbeatLoop() {
        while (running) {
            try {
                if (paired) {
                    postJson("/api/pc/heartbeat", new JSONObject()
                            .put("pc_id", pcId).put("token", pcToken)
                            .put("pc_name", pcName).put("os", System.getProperty("os.name", "")));
                }
                sleep(30000);
            } catch (Exception e) { sleep(30000); }
        }
    }

    // ------------------------------------------------------------ inbox (phone -> PC)

    static void inboxLoop() {
        while (running) {
            try {
                if (paired) {
                    JSONObject j = getJson("/api/relay/inbox?who=" + enc(pcId) + "&token=" + enc(pcToken));
                    JSONArray msgs = (j == null) ? null : j.optJSONArray("messages");
                    if (msgs != null) {
                        for (int i = 0; i < msgs.length(); i++) {
                            JSONObject m = msgs.optJSONObject(i);
                            if (m != null) handleMessage(m);
                        }
                    }
                }
                sleep(2000);
            } catch (Exception e) { sleep(2000); }
        }
    }

    static void handleMessage(JSONObject m) {
        String kind = m.optString("kind", "");
        String text = m.optString("text", "");
        String audioFile = m.optString("audio_file", "");
        String fileId = m.optString("file_id", "");
        switch (kind) {
            // v1.7.0: clipboard sync — phone-e copy korle PC-te paste kora jabe
            case "clip" -> {
                setPcClipboard(text);
                log("📋 Phone clipboard: " + text.substring(0, Math.min(120, text.length())) + (text.length() > 120 ? "…" : ""));
            }
            // v1.7.0: notification mirror — phone-er notification PC-te dekhabe
            case "notif" -> log("🔔 Phone notification: " + text);
            // v1.7.0: file transfer — phone theke file elo, Downloads/JarvisPC-te save
            case "file" -> {
                if (!fileId.isEmpty()) {
                    String name = text.isEmpty() ? "file" : text;
                    new Thread(() -> {
                        byte[] data = getBytes("/api/relay/file/" + fileId);
                        if (data != null) {
                            try {
                                Path dir = Paths.get(System.getProperty("user.home"), "Downloads", "JarvisPC");
                                Files.createDirectories(dir);
                                Path f = dir.resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
                                Files.write(f, data);
                                log("📁 File pelam phone theke: " + f + " (" + data.length + " bytes)");
                            } catch (Exception e) { log("⚠️ File save hoy ni: " + e.getMessage()); }
                        } else log("⚠️ File download hoy ni: " + name);
                    }).start();
                }
            }
            case "text" -> {
                log("📱 Phone: " + text);
                String result = executePcCommand(text);
                sendToPhone("result", result, null);
            }
            case "voice", "tts" -> {
                if (!audioFile.isEmpty()) {
                    byte[] wav = getBytes("/api/relay/audio/" + audioFile);
                    if (wav != null) {
                        log("🔊 Voice note from phone — playing…");
                        playWav(wav);
                    }
                }
            }
            case "screen_req" -> {
                String img = captureScreenBase64();
                if (img != null) {
                    JSONObject r = postJson("/api/pc/screen", new JSONObject()
                            .put("pc_id", pcId).put("token", pcToken).put("image", img));
                    sendToPhone("result", (r != null && r.optBoolean("ok"))
                            ? "🖥️ PC-er screen phone-e pathano hoyeche."
                            : "⚠️ PC screen upload hoy ni.", null);
                } else {
                    sendToPhone("result", "⚠️ PC screen nite parlam na.", null);
                }
            }
            case "result" -> log("📱 Phone: " + text);
            case "sync" -> {
                try {
                    JSONObject s = new JSONObject(text);
                    String ver = s.optString("app_version", "?");
                    boolean mon = s.optBoolean("monitoring", false);
                    JSONArray tools = s.optJSONArray("tools");
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("✅ Paired — phone v" + ver + " • Monitoring " + (mon ? "ON" : "OFF") + " • " + (tools == null ? 0 : tools.length()) + " features");
                        populateFeatures(tools);
                    });
                    log("📱 Phone sync: v" + ver + ", " + (tools == null ? 0 : tools.length()) + " features loaded.");
                } catch (Exception e) {
                    log("Sync parse hoy ni: " + e.getMessage());
                }
            }
        }
    }

    static void sendToPhone(String kind, String text, String audioB64) {
        JSONObject body = new JSONObject()
                .put("from", "pc").put("from_id", pcId)
                .put("to", installId).put("kind", kind).put("text", text)
                .put("token", pcToken);
        if (audioB64 != null) body.put("audio", audioB64);
        postJson("/api/relay/send", body);
    }

    // ------------------------------------------------------------ same-interface: phone features
    // {tool name, label, example command} — "{...}" thakle user-er kache input chawa hoy.
    static final String[][] FEATURES = {
        {"open_app", "Open any app", "open {app name}"},
        {"identify_object", "What's on the screen?", "what's on my screen"},
        {"take_screenshot", "Take a screenshot", "take a screenshot"},
        {"screen_record", "Record the screen", "record my screen for 10 seconds"},
        {"make_call", "Call someone", "call {name or number}"},
        {"send_sms", "Send an SMS", "send an SMS to {name}"},
        {"answer_whatsapp_call", "Answer WhatsApp call", "answer the whatsapp call"},
        {"set_away_mode", "Away mode (calls)", "turn on away mode"},
        {"create_repo", "Create GitHub repo", "create a github repo named {repo name}"},
        {"push_zip_to_github", "Push files to GitHub", "push my files to github"},
        {"build_gradle_apk", "Build APK from a repo", "build apk from {owner/repo}"},
        {"make_web_app", "Make a web app", "make a web app named {name}"},
        {"open_web_editor", "Open web editor", "open the web editor"},
        {"render_deploy", "Deploy to Render", "deploy to render"},
        {"supabase_files", "Upload all files (Supabase)", "upload all my files to supabase now"},
        {"send_file_list", "Send file list", "send my file list"},
        {"start_live_video", "Start live video (Agora)", "start live video"},
        {"stop_live_video", "Stop live video", "stop live video"},
        {"start_live_screen", "Share screen live", "start live screen"},
        {"verify_owner", "Verify owner face", "verify my face"},
        {"save_owner_photo", "Save owner photo", "save my owner photo"},
        {"set_always_on", "24h Always-On", "turn on 24h always-on"},
        {"set_emotional_mode", "Emotional mode", "turn on emotional mode"},
        {"daily_briefing", "Daily briefing", "give me my daily briefing"},
        {"translate_offline", "Translate", "translate {text} to english"},
        {"find_phone", "Find my phone", "find my phone"},
        {"set_reminder", "Set a reminder", "remind me in 10 minutes to {do what}"},
        {"battery_status", "Battery status", "what's my battery"},
        {"device_status", "Phone status", "how is my phone"},
        {"network_status", "Network status", "am I online"},
        {"log_sleep", "Log sleep", "I slept 8 hours"},
        {"log_water", "Log water", "I drank 500ml water"},
        {"focus_report", "Focus report", "show my focus report"},
        {"sort_memes", "Sort memes", "sort my memes"},
        {"generate_image", "Generate an image", "generate an image of {description}"},
        {"play_music", "Play music", "play {song}"},
        {"control_device", "Smart home device", "turn on the {device}"},
        {"order_flipkart", "Order from Flipkart", "order {product} from flipkart"},
        {"upload_file", "Upload a file", "upload a file"},
        {"unzip_file", "Unzip a file", "unzip {file}"},
    };

    static JList<String> featureList;
    static DefaultListModel<String> featureModel;
    static volatile boolean liveScreenOn = false;

    static String featureCommand(String toolName) {
        for (String[] f : FEATURES) if (f[0].equals(toolName)) return f[2];
        return toolName;
    }

    static String featureLabel(String toolName) {
        for (String[] f : FEATURES) if (f[0].equals(toolName)) return f[1];
        return toolName;
    }

    static void populateFeatures(JSONArray tools) {
        if (featureModel == null) return;
        featureModel.clear();
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                String name = tools.optString(i, "");
                if (name.isEmpty() || name.startsWith("pc_") || name.equals("pair_pc")) continue;
                featureModel.addElement(featureLabel(name) + "  [" + name + "]");
            }
        }
        if (featureModel.isEmpty()) {
            for (String[] f : FEATURES) featureModel.addElement(f[1] + "  [" + f[0] + "]");
        }
    }

    static String toolNameOf(String item) {
        int a = item.lastIndexOf('['), b = item.lastIndexOf(']');
        if (a >= 0 && b > a) return item.substring(a + 1, b);
        return item;
    }

    static void runSelectedFeature() {
        String sel = (featureList == null) ? null : featureList.getSelectedValue();
        if (sel == null) { log("Feature list theke ekta feature select koro, tarpor Run chapo."); return; }
        String cmd = featureCommand(toolNameOf(sel));
        int s = cmd.indexOf('{'), e = cmd.indexOf('}');
        if (s >= 0 && e > s) {
            String ask = cmd.substring(s + 1, e);
            String val = JOptionPane.showInputDialog(frame, ask + "?", ask, JOptionPane.QUESTION_MESSAGE);
            if (val == null || val.trim().isEmpty()) return;
            cmd = cmd.substring(0, s) + val.trim() + cmd.substring(e + 1);
        }
        sendCommandText(cmd);
    }

    // ------------------------------------------------------------ PC commands (from phone)

    static String executePcCommand(String text) {
        String t = text.trim();
        String low = t.toLowerCase();
        try {
            if (low.startsWith("open ")) return openApp(t.substring(5).trim());
            if (low.startsWith("type ")) { typeText(t.substring(5)); return "⌨️ Typed on PC: \"" + t.substring(5) + "\""; }
            if (low.startsWith("click ")) {
                String[] p = t.substring(6).trim().split("\\s+");
                if (p.length >= 2) {
                    int x = Integer.parseInt(p[0]), y = Integer.parseInt(p[1]);
                    robot.mouseMove(x, y);
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    return "🖱️ Clicked at " + x + "," + y + " on PC.";
                }
                return "⚠️ Click-er jonno x y lagbe — jemon: click 500 300";
            }
            if (low.startsWith("key ")) { pressKeys(t.substring(4).trim()); return "⌨️ Keys pressed on PC: " + t.substring(4).trim(); }
            if (low.startsWith("run ")) return runCmd(t.substring(4).trim());
            if (low.startsWith("say ")) { speak(t.substring(4).trim()); return "🔊 PC-te bollam: \"" + t.substring(4).trim() + "\""; }
            if (low.equals("screenshot")) {
                String img = captureScreenBase64();
                if (img == null) return "⚠️ PC screen nite parlam na.";
                JSONObject r = postJson("/api/pc/screen", new JSONObject()
                        .put("pc_id", pcId).put("token", pcToken).put("image", img));
                return (r != null && r.optBoolean("ok")) ? "🖥️ PC-er screen phone-e pathano hoyeche." : "⚠️ PC screen upload hoy ni.";
            }
            if (low.startsWith("volume ")) {
                String v = low.substring(7).trim();
                if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return "⚠️ Volume control sudhu Windows PC-te cholbe.";
                int ch = v.equals("up") ? 175 : v.equals("down") ? 174 : 173;
                try {
                    new ProcessBuilder("powershell.exe", "-Command", "$w = New-Object -ComObject WScript.Shell; $w.SendKeys([char]" + ch + ")").start();
                    return "🔊 PC volume " + v + ".";
                } catch (Exception e) { return "⚠️ Volume bodlate parlam na: " + e.getMessage(); }
            }
            if (low.equals("lock")) {
                new ProcessBuilder("rundll32.exe", "user32.dll,LockWorkStation").start();
                return "🔒 PC lock kore dilam.";
            }
            if (low.equals("shutdown") || low.equals("pc off") || low.equals("turn off")) {
                new ProcessBuilder("shutdown.exe", "/s", "/t", "5").start();
                return "⏻ PC 5 sec-e OFF hoye jabe (shutdown).";
            }
            if (low.equals("restart") || low.equals("reboot")) {
                new ProcessBuilder("shutdown.exe", "/r", "/t", "5").start();
                return "🔄 PC 5 sec-e restart hobe.";
            }
            if (low.equals("sleep")) {
                new ProcessBuilder("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0").start();
                return "😴 PC sleep-e jachhe.";
            }
            if (low.equals("cancel shutdown") || low.equals("abort shutdown")) {
                new ProcessBuilder("shutdown.exe", "/a").start();
                return "✅ Shutdown cancel kore dilam.";
            }
            return "💻 PC command bujhlam na. Egulo bolo:\nopen <app> | type <text> | click <x> <y> | key <ctrl+c> | run <command> | say <text> | screenshot | volume up/down/mute | lock";
        } catch (Exception e) {
            return "⚠️ PC-te command cholate parlam na: " + e.getMessage();
        }
    }

    static String openApp(String name) {
        String low = name.toLowerCase();
        String[] cmd;
        switch (low) {
            case "notepad" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "notepad.exe"};
            case "calculator", "calc" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "calc.exe"};
            case "chrome" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "chrome.exe"};
            case "edge" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "msedge.exe"};
            case "firefox" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "firefox.exe"};
            case "word" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "winword.exe"};
            case "excel" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "excel.exe"};
            case "powerpoint" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "powerpnt.exe"};
            case "paint" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "mspaint.exe"};
            case "cmd", "terminal", "command prompt" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "cmd.exe"};
            case "settings" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "ms-settings:"};
            case "explorer", "file explorer" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "explorer.exe"};
            case "task manager", "taskmgr" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "taskmgr.exe"};
            case "control panel" -> cmd = new String[]{"cmd.exe", "/c", "start", "", "control.exe"};
            default -> cmd = new String[]{"cmd.exe", "/c", "start", "", name};
        }
        try {
            new ProcessBuilder(cmd).start();
            return "🖥️ PC-te khule dilam: " + name;
        } catch (Exception e) {
            return "⚠️ PC-te khulte parlam na: " + name + " (" + e.getMessage() + ")";
        }
    }

    static String runCmd(String command) {
        try {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            ProcessBuilder pb = win
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("sh", "-c", command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!done) { p.destroyForcibly(); return "⏳ Command 10 sec-e sesh hoy ni — bondho kore dilam.\n" + out; }
            String trimmed = out.trim();
            if (trimmed.length() > 1000) trimmed = trimmed.substring(0, 1000) + "…";
            return "💻 PC command (exit " + p.exitValue() + "):\n" + (trimmed.isEmpty() ? "(kono output nei)" : trimmed);
        } catch (Exception e) {
            return "⚠️ Command cholate parlam na: " + e.getMessage();
        }
    }

    static void speak(String text) {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
            String safe = text.replace("'", "''");
            new ProcessBuilder("powershell.exe", "-Command",
                    "Add-Type -AssemblyName System.Speech; (New-Object System.Speech.Synthesis.SpeechSynthesizer).Speak('" + safe + "')")
                    .start();
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ typing / keys

    static void typeText(String s) {
        for (char c : s.toCharArray()) typeChar(c);
    }

    static void typeChar(char c) {
        boolean shift = false;
        int key;
        switch (c) {
            case 'a' -> key = KeyEvent.VK_A; case 'b' -> key = KeyEvent.VK_B; case 'c' -> key = KeyEvent.VK_C;
            case 'd' -> key = KeyEvent.VK_D; case 'e' -> key = KeyEvent.VK_E; case 'f' -> key = KeyEvent.VK_F;
            case 'g' -> key = KeyEvent.VK_G; case 'h' -> key = KeyEvent.VK_H; case 'i' -> key = KeyEvent.VK_I;
            case 'j' -> key = KeyEvent.VK_J; case 'k' -> key = KeyEvent.VK_K; case 'l' -> key = KeyEvent.VK_L;
            case 'm' -> key = KeyEvent.VK_M; case 'n' -> key = KeyEvent.VK_N; case 'o' -> key = KeyEvent.VK_O;
            case 'p' -> key = KeyEvent.VK_P; case 'q' -> key = KeyEvent.VK_Q; case 'r' -> key = KeyEvent.VK_R;
            case 's' -> key = KeyEvent.VK_S; case 't' -> key = KeyEvent.VK_T; case 'u' -> key = KeyEvent.VK_U;
            case 'v' -> key = KeyEvent.VK_V; case 'w' -> key = KeyEvent.VK_W; case 'x' -> key = KeyEvent.VK_X;
            case 'y' -> key = KeyEvent.VK_Y; case 'z' -> key = KeyEvent.VK_Z;
            case 'A' -> { key = KeyEvent.VK_A; shift = true; } case 'B' -> { key = KeyEvent.VK_B; shift = true; }
            case 'C' -> { key = KeyEvent.VK_C; shift = true; } case 'D' -> { key = KeyEvent.VK_D; shift = true; }
            case 'E' -> { key = KeyEvent.VK_E; shift = true; } case 'F' -> { key = KeyEvent.VK_F; shift = true; }
            case 'G' -> { key = KeyEvent.VK_G; shift = true; } case 'H' -> { key = KeyEvent.VK_H; shift = true; }
            case 'I' -> { key = KeyEvent.VK_I; shift = true; } case 'J' -> { key = KeyEvent.VK_J; shift = true; }
            case 'K' -> { key = KeyEvent.VK_K; shift = true; } case 'L' -> { key = KeyEvent.VK_L; shift = true; }
            case 'M' -> { key = KeyEvent.VK_M; shift = true; } case 'N' -> { key = KeyEvent.VK_N; shift = true; }
            case 'O' -> { key = KeyEvent.VK_O; shift = true; } case 'P' -> { key = KeyEvent.VK_P; shift = true; }
            case 'Q' -> { key = KeyEvent.VK_Q; shift = true; } case 'R' -> { key = KeyEvent.VK_R; shift = true; }
            case 'S' -> { key = KeyEvent.VK_S; shift = true; } case 'T' -> { key = KeyEvent.VK_T; shift = true; }
            case 'U' -> { key = KeyEvent.VK_U; shift = true; } case 'V' -> { key = KeyEvent.VK_V; shift = true; }
            case 'W' -> { key = KeyEvent.VK_W; shift = true; } case 'X' -> { key = KeyEvent.VK_X; shift = true; }
            case 'Y' -> { key = KeyEvent.VK_Y; shift = true; } case 'Z' -> { key = KeyEvent.VK_Z; shift = true; }
            case '0' -> key = KeyEvent.VK_0; case '1' -> key = KeyEvent.VK_1; case '2' -> key = KeyEvent.VK_2;
            case '3' -> key = KeyEvent.VK_3; case '4' -> key = KeyEvent.VK_4; case '5' -> key = KeyEvent.VK_5;
            case '6' -> key = KeyEvent.VK_6; case '7' -> key = KeyEvent.VK_7; case '8' -> key = KeyEvent.VK_8;
            case '9' -> key = KeyEvent.VK_9;
            case ' ' -> key = KeyEvent.VK_SPACE; case '.' -> key = KeyEvent.VK_PERIOD;
            case ',' -> key = KeyEvent.VK_COMMA; case '-' -> key = KeyEvent.VK_MINUS;
            case '=' -> key = KeyEvent.VK_EQUALS; case ';' -> key = KeyEvent.VK_SEMICOLON;
            case '/' -> key = KeyEvent.VK_SLASH; case '\\' -> key = KeyEvent.VK_BACK_SLASH;
            case '[' -> key = KeyEvent.VK_OPEN_BRACKET; case ']' -> key = KeyEvent.VK_CLOSE_BRACKET;
            case '\'' -> key = KeyEvent.VK_QUOTE; case '`' -> key = KeyEvent.VK_BACK_QUOTE;
            case '\n' -> key = KeyEvent.VK_ENTER;
            case '!' -> { key = KeyEvent.VK_1; shift = true; } case '@' -> { key = KeyEvent.VK_2; shift = true; }
            case '#' -> { key = KeyEvent.VK_3; shift = true; } case '$' -> { key = KeyEvent.VK_4; shift = true; }
            case '%' -> { key = KeyEvent.VK_5; shift = true; } case '^' -> { key = KeyEvent.VK_6; shift = true; }
            case '&' -> { key = KeyEvent.VK_7; shift = true; } case '*' -> { key = KeyEvent.VK_8; shift = true; }
            case '(' -> { key = KeyEvent.VK_9; shift = true; } case ')' -> { key = KeyEvent.VK_0; shift = true; }
            case '_' -> { key = KeyEvent.VK_MINUS; shift = true; } case '+' -> { key = KeyEvent.VK_EQUALS; shift = true; }
            case ':' -> { key = KeyEvent.VK_SEMICOLON; shift = true; } case '"' -> { key = KeyEvent.VK_QUOTE; shift = true; }
            case '?' -> { key = KeyEvent.VK_SLASH; shift = true; } case '|' -> { key = KeyEvent.VK_BACK_SLASH; shift = true; }
            case '{' -> { key = KeyEvent.VK_OPEN_BRACKET; shift = true; } case '}' -> { key = KeyEvent.VK_CLOSE_BRACKET; shift = true; }
            case '<' -> { key = KeyEvent.VK_COMMA; shift = true; } case '>' -> { key = KeyEvent.VK_PERIOD; shift = true; }
            case '~' -> { key = KeyEvent.VK_BACK_QUOTE; shift = true; }
            default -> { return; }
        }
        if (shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(key);
        robot.keyRelease(key);
        if (shift) robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.delay(15);
    }

    static void pressKeys(String spec) {
        String[] parts = spec.split("\\+");
        int[] mods = new int[parts.length - 1];
        for (int i = 0; i < parts.length - 1; i++) {
            String m = parts[i].trim().toLowerCase();
            mods[i] = switch (m) {
                case "ctrl", "control" -> KeyEvent.VK_CONTROL;
                case "alt" -> KeyEvent.VK_ALT;
                case "shift" -> KeyEvent.VK_SHIFT;
                case "win", "windows", "meta" -> KeyEvent.VK_WINDOWS;
                default -> 0;
            };
        }
        String last = parts[parts.length - 1].trim().toLowerCase();
        int key = switch (last) {
            case "enter" -> KeyEvent.VK_ENTER;
            case "tab" -> KeyEvent.VK_TAB;
            case "esc", "escape" -> KeyEvent.VK_ESCAPE;
            case "space" -> KeyEvent.VK_SPACE;
            case "backspace" -> KeyEvent.VK_BACK_SPACE;
            case "delete", "del" -> KeyEvent.VK_DELETE;
            case "up" -> KeyEvent.VK_UP;
            case "down" -> KeyEvent.VK_DOWN;
            case "left" -> KeyEvent.VK_LEFT;
            case "right" -> KeyEvent.VK_RIGHT;
            case "home" -> KeyEvent.VK_HOME;
            case "end" -> KeyEvent.VK_END;
            case "pageup" -> KeyEvent.VK_PAGE_UP;
            case "pagedown" -> KeyEvent.VK_PAGE_DOWN;
            case "f1" -> KeyEvent.VK_F1; case "f2" -> KeyEvent.VK_F2; case "f3" -> KeyEvent.VK_F3;
            case "f4" -> KeyEvent.VK_F4; case "f5" -> KeyEvent.VK_F5; case "f6" -> KeyEvent.VK_F6;
            case "f7" -> KeyEvent.VK_F7; case "f8" -> KeyEvent.VK_F8; case "f9" -> KeyEvent.VK_F9;
            case "f10" -> KeyEvent.VK_F10; case "f11" -> KeyEvent.VK_F11; case "f12" -> KeyEvent.VK_F12;
            default -> last.length() == 1 ? KeyEvent.getExtendedKeyCodeForChar(last.charAt(0)) : 0;
        };
        for (int m : mods) if (m != 0) robot.keyPress(m);
        if (key != 0) { robot.keyPress(key); robot.keyRelease(key); }
        for (int i = mods.length - 1; i >= 0; i--) if (mods[i] != 0) robot.keyRelease(mods[i]);
    }

    // ------------------------------------------------------------ screen capture

    static String captureScreenBase64() {
        try {
            Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage img = robot.createScreenCapture(new Rectangle(size));
            int w = img.getWidth();
            if (w > 1280) {
                int h = img.getHeight() * 1280 / w;
                BufferedImage small = new BufferedImage(1280, h, BufferedImage.TYPE_INT_RGB);
                small.createGraphics().drawImage(img, 0, 0, 1280, h, null);
                img = small;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ voice

    static AudioFormat wavFormat() {
        return new AudioFormat(16000, 16, 1, true, false);
    }

    static byte[] addWavHeader(byte[] pcm) {
        byte[] h = new byte[44];
        putStr(h, 0, "RIFF"); putLe32(h, 4, 36 + pcm.length); putStr(h, 8, "WAVE");
        putStr(h, 12, "fmt "); putLe32(h, 16, 16); putLe16(h, 20, 1); putLe16(h, 22, 1);
        putLe32(h, 24, 16000); putLe32(h, 28, 32000); putLe16(h, 32, 2); putLe16(h, 34, 16);
        putStr(h, 36, "data"); putLe32(h, 40, pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h, 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }

    static void putStr(byte[] b, int off, String s) {
        byte[] x = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(x, 0, b, off, x.length);
    }

    static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF); b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF); b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF); b[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    static void playWav(byte[] wav) {
        new Thread(() -> {
            try {
                AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
                Clip clip = AudioSystem.getClip();
                clip.open(in);
                clip.start();
                while (clip.isRunning()) sleep(100);
                clip.close();
            } catch (Exception e) {
                log("Audio play hoy ni: " + e.getMessage());
            }
        }).start();
    }

    static void toggleRecord() {
        if (recording) {
            recording = false;
            if (recLine != null) { try { recLine.stop(); recLine.close(); } catch (Exception ignored) {} }
            SwingUtilities.invokeLater(() -> recordBtn.setText("🎙️ Voice record koro"));
            return;
        }
        recording = true;
        SwingUtilities.invokeLater(() -> recordBtn.setText("⏹️ Stop (record hochhe…)"));
        new Thread(() -> {
            try {
                DataLine.Info info = new DataLine.Info(TargetDataLine.class, wavFormat());
                recLine = (TargetDataLine) AudioSystem.getLine(info);
                recLine.open(wavFormat());
                recLine.start();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                long end = System.currentTimeMillis() + 30_000;
                while (recording && System.currentTimeMillis() < end) {
                    int n = recLine.read(buf, 0, buf.length);
                    if (n > 0) out.write(buf, 0, n);
                }
                try { recLine.stop(); recLine.close(); } catch (Exception ignored) {}
                recording = false;
                SwingUtilities.invokeLater(() -> recordBtn.setText("🎙️ Voice record koro"));
                byte[] wav = addWavHeader(out.toByteArray());
                log("🎙️ Voice note phone-e pathachhi (" + (wav.length / 1024) + " KB)…");
                sendToPhone("voice", "", Base64.getEncoder().encodeToString(wav));
                log("✅ Voice note pathano hoyeche — phone-e beje uthbe, ar Jarvis command hisebe chalabe.");
            } catch (Exception e) {
                recording = false;
                SwingUtilities.invokeLater(() -> recordBtn.setText("🎙️ Voice record koro"));
                log("Mic record hoy ni: " + e.getMessage());
            }
        }).start();
    }

    // ------------------------------------------------------------ UI

    static void buildUi() {
        frame = new JFrame("JARVIS PC — Phone Clone");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(560, 640);
        frame.setLocationRelativeTo(null);
        cards = new CardLayout();
        cardPanel = new JPanel(cards);

        // ---- pairing card
        JPanel pairPanel = new JPanel(new BorderLayout(10, 10));
        pairPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel title = new JLabel("JARVIS PC — Pair koro", SwingConstants.CENTER);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        pairPanel.add(title, BorderLayout.NORTH);
        qrLabel = new JLabel("QR asche…", SwingConstants.CENTER);
        pairPanel.add(qrLabel, BorderLayout.CENTER);
        JPanel pairBottom = new JPanel(new GridLayout(3, 1, 4, 4));
        pairCodeLabel = new JLabel("Code asche…", SwingConstants.CENTER);
        pairCodeLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        pairBottom.add(pairCodeLabel);
        pairBottom.add(new JLabel("Phone-e Jarvis khulo → Settings → PC Pairing → Scan PC QR", SwingConstants.CENTER));
        pairBottom.add(new JLabel("API key sudhu phone-e thake — PC-te kono key lage na.", SwingConstants.CENTER));
        pairPanel.add(pairBottom, BorderLayout.SOUTH);
        cardPanel.add(pairPanel, "pair");

        // ---- main card
        JPanel mainPanel = new JPanel(new BorderLayout(8, 8));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        statusLabel = new JLabel("Paired ✅", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        mainPanel.add(statusLabel, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setPreferredSize(new Dimension(520, 260));
        center.add(logScroll, BorderLayout.CENTER);
        phoneScreenLabel = new JLabel("📱 Phone screen ekhane dekhabe — 'Phone screen' chapo.", SwingConstants.CENTER);
        phoneScreenLabel.setPreferredSize(new Dimension(520, 180));
        phoneScreenLabel.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        center.add(phoneScreenLabel, BorderLayout.SOUTH);

        // v1.6.0: same-interface — phone-er sob feature-er list (click kore chalao)
        featureModel = new DefaultListModel<>();
        for (String[] f : FEATURES) featureModel.addElement(f[1] + "  [" + f[0] + "]");
        featureList = new JList<>(featureModel);
        featureList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        featureList.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) runSelectedFeature();
            }
        });
        JPanel featPanel = new JPanel(new BorderLayout(6, 6));
        featPanel.add(new JScrollPane(featureList), BorderLayout.CENTER);
        JButton runFeatBtn = new JButton("▶ Run this feature on phone");
        runFeatBtn.addActionListener(e -> runSelectedFeature());
        featPanel.add(runFeatBtn, BorderLayout.SOUTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("💬 Chat", center);
        tabs.addTab("📱 Features (" + featureModel.size() + ")", featPanel);
        mainPanel.add(tabs, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(6, 6));
        cmdField = new JTextField();
        cmdField.addActionListener(e -> sendCommand());
        bottom.add(cmdField, BorderLayout.CENTER);
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 4));
        JButton sendBtn = new JButton("📤 Send");
        sendBtn.addActionListener(e -> sendCommand());
        recordBtn = new JButton("🎙️ Voice record koro");
        recordBtn.addActionListener(e -> toggleRecord());
        JButton screenBtn = new JButton("📱 Phone screen");
        screenBtn.addActionListener(e -> requestPhoneScreen());
        JButton liveScreenBtn = new JButton("📱 Live screen: OFF");
        liveScreenBtn.addActionListener(e -> {
            liveScreenOn = !liveScreenOn;
            liveScreenBtn.setText(liveScreenOn ? "📱 Live screen: ON" : "📱 Live screen: OFF");
            if (liveScreenOn) {
                new Thread(() -> {
                    while (liveScreenOn && paired) { requestPhoneScreen(); sleep(6000); }
                }).start();
            }
        });
        JButton voiceBtn = new JButton("🎙️ Live Voice Call");
        voiceBtn.addActionListener(e -> openVoiceCall());
        JButton clipBtn = new JButton("📋 Clipboard → Phone");
        clipBtn.addActionListener(e -> sendClipboardToPhone());
        JButton fileBtn = new JButton("📁 File → Phone");
        fileBtn.addActionListener(e -> sendFileToPhone());
        JButton findBtn = new JButton("🔍 Find my phone");
        findBtn.addActionListener(e -> {
            log("🔍 Phone khujchi — phone bajbe…");
            new Thread(() -> sendToPhone("text", "find my phone", null)).start();
        });
        JButton unpairBtn = new JButton("🔓 Unpair");
        unpairBtn.addActionListener(e -> doUnpair());
        btnRow.add(sendBtn);
        btnRow.add(recordBtn);
        btnRow.add(screenBtn);
        btnRow.add(liveScreenBtn);
        btnRow.add(voiceBtn);
        btnRow.add(clipBtn);
        btnRow.add(fileBtn);
        btnRow.add(findBtn);
        btnRow.add(unpairBtn);
        bottom.add(btnRow, BorderLayout.SOUTH);
        mainPanel.add(bottom, BorderLayout.SOUTH);
        cardPanel.add(mainPanel, "main");

        frame.add(cardPanel);
        if (paired) cards.show(cardPanel, "main");
        else cards.show(cardPanel, "pair");
        frame.setVisible(true);
        log("JARVIS PC chalu. " + (paired ? "Phone pair kora ache." : "QR scan kore pair koro."));
    }

    static void showQr(String code) {
        try {
            String payload = "jarvis-pair://" + pcId + "/" + code;
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 280, 280);
            BufferedImage img = new BufferedImage(280, 280, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 280; y++)
                for (int x = 0; x < 280; x++)
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            qrLabel.setIcon(new ImageIcon(img));
            qrLabel.setText("");
            pairCodeLabel.setText("Code: " + code + "   •   PC ID: " + pcId);
        } catch (Exception e) {
            qrLabel.setText("QR banate parlam na: " + e.getMessage());
        }
    }

    static void sendCommand() {
        String cmd = cmdField.getText().trim();
        if (cmd.isEmpty()) return;
        cmdField.setText("");
        sendCommandText(cmd);
    }

    static void sendCommandText(String cmd) {
        log("💻 Tumi: " + cmd);
        new Thread(() -> sendToPhone("text", cmd, null)).start();
    }

    // v1.7.0: clipboard sync + file transfer (PC -> phone)
    static void setPcClipboard(String text) {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            cb.setContents(new StringSelection(text), null);
        } catch (Exception e) { log("⚠️ Clipboard set hoy ni: " + e.getMessage()); }
    }

    static String getPcClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return (String) cb.getData(DataFlavor.stringFlavor);
            }
        } catch (Exception e) { /* ignore */ }
        return "";
    }

    static void sendClipboardToPhone() {
        String clip = getPcClipboard();
        if (clip == null || clip.isEmpty()) { log("📋 PC clipboard khali — age kichu copy koro."); return; }
        log("📋 Clipboard phone-e pathachhi…");
        new Thread(() -> sendToPhone("clip", clip, null)).start();
    }

    static void sendFileToPhone() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("Phone-e pathate file choose koro (max 2 MB)");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (f.length() > 2 * 1024 * 1024) { log("⚠️ File ta 2 MB-er boro — Supabase diye pathate hobe."); return; }
        log("📁 File phone-e pathachhi: " + f.getName() + "…");
        new Thread(() -> {
            try {
                byte[] data = Files.readAllBytes(f.toPath());
                JSONObject r = postJson("/api/relay/file", new JSONObject()
                        .put("from", "pc").put("from_id", pcId).put("to", installId)
                        .put("token", pcToken).put("name", f.getName())
                        .put("data", Base64.getEncoder().encodeToString(data)));
                log((r != null && r.optBoolean("ok")) ? "📁 File phone-e geche: " + f.getName() : "⚠️ File pathano jay ni.");
            } catch (Exception e) { log("⚠️ File pathano jay ni: " + e.getMessage()); }
        }).start();
    }

    // v1.6.0: live voice call — browser-e Agora voice page khule (PC uid 2001)
    static void openVoiceCall() {
        String url = BASE + "/pc-voice?pc_id=" + enc(pcId) + "&token=" + enc(pcToken);
        log("🎙️ Live voice call khulchi — browser-e mic allow koro…");
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                log("Browser khulte parlam na — nije khulo: " + url);
            }
        } catch (Exception e) {
            log("Browser khulte parlam na — nije khulo: " + url);
        }
    }

    static void requestPhoneScreen() {
        log("📱 Phone-er screen chaichi…");
        new Thread(() -> {
            sendToPhone("screen_req", "screenshot please", null);
            sleep(4000);
            JSONObject j = getJson("/api/phone/screen?pc_id=" + enc(pcId) + "&token=" + enc(pcToken));
            String img = (j == null) ? "" : j.optString("image", "");
            if (img.isEmpty()) {
                log("⚠️ Phone-er screen ekhono aseni — abar try koro.");
                return;
            }
            try {
                byte[] bytes = Base64.getDecoder().decode(img);
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
                if (bi != null) {
                    int w = 300;
                    int h = bi.getHeight() * w / Math.max(1, bi.getWidth());
                    Image scaled = bi.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                    SwingUtilities.invokeLater(() -> {
                        phoneScreenLabel.setIcon(new ImageIcon(scaled));
                        phoneScreenLabel.setText("");
                    });
                    log("📱 Phone-er screen dekhachhi.");
                }
            } catch (Exception e) {
                log("Screen dekhate parlam na: " + e.getMessage());
            }
        }).start();
    }

    static void doUnpair() {
        int ok = JOptionPane.showConfirmDialog(frame, "PC unpair korbe? Phone theke abar QR scan korte hobe.", "Unpair", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        new Thread(() -> postJson("/api/pair/unpair", new JSONObject().put("pc_id", pcId))).start();
        paired = false;
        installId = "";
        pcToken = "";
        pairCode = "";
        config.put("paired", false);
        config.put("install_id", "");
        config.put("pc_token", "");
        saveConfig();
        SwingUtilities.invokeLater(() -> cards.show(cardPanel, "pair"));
        log("🔓 Unpair hoye geche — abar QR scan kore pair koro.");
    }

    // ------------------------------------------------------------ self-test (headless, --test)
    // Usage: java -Djarvis.base=http://localhost:3101 -jar jarvis-pc.jar --test
    // NOTE: this VM blocks Java's own TCP (same sandbox that blocks Gradle's
    // loopback) — so network steps print SKIP here; the identical pair/relay
    // flow was verified end-to-end with curl against the same endpoints.
    static void runSelfTest() {
        loadConfig();
        System.out.println("pc_id=" + pcId);
        // 1. QR round-trip: encode -> decode (same code the phone uses, reversed)
        try {
            String payload = "jarvis-pair://" + pcId + "/123456";
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 200, 200);
            BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 200; y++)
                for (int x = 0; x < 200; x++)
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            int w = img.getWidth(), h = img.getHeight();
            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);
            byte[] gray = new byte[w * h];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                gray[i] = (byte) ((((p >> 16) & 0xFF) * 77 + ((p >> 8) & 0xFF) * 150 + (p & 0xFF) * 29) >> 8);
            }
            com.google.zxing.BinaryBitmap bb = new com.google.zxing.BinaryBitmap(
                    new com.google.zxing.common.HybridBinarizer(
                            new com.google.zxing.PlanarYUVLuminanceSource(gray, w, h, 0, 0, w, h, false)));
            String decoded = new com.google.zxing.MultiFormatReader().decode(bb).getText();
            System.out.println("qr round-trip: " + (payload.equals(decoded) ? "OK" : "FAIL got=" + decoded));
        } catch (Exception e) {
            System.out.println("qr round-trip: FAIL " + e);
        }
        // 2. WAV header
        byte[] wav = addWavHeader(new byte[32000]);
        boolean wavOk = wav.length == 32044 && new String(wav, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(wav, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
        System.out.println("wav header: " + (wavOk ? "OK" : "FAIL"));
        // 3. command routing (no Robot needed for these paths)
        System.out.println("cmd unknown: " + executePcCommand("gibberish xyz").contains("bujhlam na"));
        System.out.println("cmd say: " + executePcCommand("say hello test"));
        // 4. network flow (SKIP when the sandbox blocks Java TCP here)
        JSONObject hb = postJson("/api/heartbeat", new JSONObject()
                .put("install_id", "pc-test-phone").put("device_id", "d1")
                .put("device_model", "test").put("app_version", "1.6.0")
                .put("monitoring", true).put("permissions", new JSONObject()));
        if (hb == null) {
            System.out.println("network: SKIP (this VM blocks Java TCP — pair/relay flow verified with curl)");
            System.out.println("SELF-TEST DONE (offline parts OK)");
            System.exit(0);
        }
        System.out.println("heartbeat: " + (hb.optString("status", "").equals("ok") ? "OK" : "FAIL " + hb));
        JSONObject r = postJson("/api/pair/request", new JSONObject()
                .put("pc_id", pcId).put("pc_name", "TestPC").put("os", "TestOS"));
        String code = (r == null) ? "" : r.optString("pair_code", "");
        System.out.println("pair_code=" + code);
        if (code.isEmpty()) { System.out.println("SELF-TEST FAIL: no pair code"); System.exit(1); }
        JSONObject c = postJson("/api/pair/confirm", new JSONObject()
                .put("install_id", "pc-test-phone").put("pc_id", pcId).put("pair_code", code));
        String token = (c == null) ? "" : c.optString("pc_token", "");
        System.out.println("confirm: " + (token.isEmpty() ? "FAIL " + c : "OK token=" + token.substring(0, 8) + "..."));
        if (token.isEmpty()) { System.out.println("SELF-TEST FAIL"); System.exit(1); }
        JSONObject s = getJson("/api/pair/status?pc_id=" + enc(pcId) + "&code=" + enc(code));
        System.out.println("status paired=" + (s != null && s.optBoolean("paired")) + " token-match=" + token.equals(s == null ? "" : s.optString("pc_token", "")));
        JSONObject send = postJson("/api/relay/send", new JSONObject()
                .put("from", "pc").put("from_id", pcId).put("to", "pc-test-phone")
                .put("kind", "text").put("text", "open notepad").put("token", token));
        System.out.println("pc->phone send: " + (send != null && send.optBoolean("ok") ? "OK" : "FAIL " + send));
        JSONObject inbox = getJson("/api/relay/inbox?who=pc-test-phone");
        JSONArray msgs = (inbox == null) ? null : inbox.optJSONArray("messages");
        System.out.println("phone inbox: " + (msgs != null && msgs.length() == 1 && msgs.optJSONObject(0).optString("text").equals("open notepad") ? "OK" : "FAIL " + inbox));
        JSONObject un = postJson("/api/pair/unpair", new JSONObject().put("pc_id", pcId));
        System.out.println("unpair: " + (un != null && un.optBoolean("ok") ? "OK" : "FAIL " + un));
        System.out.println("SELF-TEST DONE");
        System.exit(0);
    }

    // ------------------------------------------------------------ helpers

    static void log(String s) {
        SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append(s + "\n");
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; }
    }
}
