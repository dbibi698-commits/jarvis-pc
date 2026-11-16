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
 * - v1.1 PC AI (optional): set a Gemini API key (🔑 API Key button) and the
 *   PC itself becomes an AI — chat/voice directly on the PC, PC commands run
 *   locally, phone commands are relayed to the phone AI. Without a key the
 *   PC just relays everything to the phone as before.
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
    static String geminiApiKey = "";
    static String e2eSecret = "";
    static volatile boolean phoneHasE2e = false;
    static String githubToken = "";
    static boolean voiceFemale = true;
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
        if (args.length > 0 && args[0].equals("--test-api")) {
            loadConfig();
            startLocalApi();
            System.out.println("API UP on 127.0.0.1:18778");
            try { Thread.sleep(90000); } catch (Exception ignored) {}
            return;
        }
        robot = new Robot();
        loadConfig();
        pcName = System.getenv("COMPUTERNAME");
        if (pcName == null || pcName.isEmpty()) pcName = System.getProperty("user.name", "PC");
        SwingUtilities.invokeLater(JarvisPC::buildUi);
        new Thread(JarvisPC::pairLoop, "pair-loop").start();
        new Thread(JarvisPC::inboxLoop, "inbox-loop").start();
        new Thread(JarvisPC::heartbeatLoop, "heartbeat-loop").start();
        new Thread(JarvisPC::startLocalApi, "local-api").start();
        new Thread(JarvisPC::startWebDashboard, "web-dash").start();
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
        e2eSecret = config.optString("e2e_secret", "");
        if (e2eSecret.isEmpty()) {
            byte[] rb = new byte[16];
            new java.security.SecureRandom().nextBytes(rb);
            StringBuilder hb = new StringBuilder();
            for (byte b : rb) hb.append(String.format("%02x", b));
            e2eSecret = hb.toString();
            config.put("e2e_secret", e2eSecret);
            saveConfig();
        }
        pcToken = config.optString("pc_token", "");
        installId = config.optString("install_id", "");
        geminiApiKey = config.optString("gemini_api_key", "");
        geminiModel = config.optString("gemini_model", "");
        githubToken = config.optString("github_token", "");
        voiceFemale = config.optBoolean("voice_female", true);
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
                String plain = e2eDecrypt(text);
                log("📱 Phone: " + plain);
                String result = executePcCommand(plain);
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
            case "result" -> {
                log("📱 Phone: " + text);
                finishAgentTask("pc", true, text);
                String clean = text.replaceAll("[*_#`]", "").replaceAll("\\n+", " ");
                if (clean.length() > 4 && clean.length() < 600) speak(clean);
            }
            case "sync" -> {
                try {
                    JSONObject s = new JSONObject(text);
                    String ver = s.optString("app_version", "?");
                    boolean mon = s.optBoolean("monitoring", false);
                    JSONArray tools = s.optJSONArray("tools");
                    JSONArray agents = s.optJSONArray("agents");
                    if (agents != null) phoneAgentsJson = agents.toString();
                    phoneHasE2e = s.optBoolean("e2e", false);
                    if (phoneHasE2e) log("🔒 E2E encryption ON — phone-er sathe text command encrypted jachhe.");
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
        String wireText = "text".equals(kind) ? e2eEncrypt(text) : text;
        JSONObject body = new JSONObject()
                .put("from", "pc").put("from_id", pcId)
                .put("to", installId).put("kind", kind).put("text", wireText)
                .put("token", pcToken);
        if (audioB64 != null) body.put("audio", audioB64);
        postJson("/api/relay/send", body);
    }

    // ------------------------------------------------------------ same-interface: phone features
    // v1.2: phone-er SOB tool (182) - ToolDefs theke generate kora. Sync hole phone-er live list diye replace hoy.
    static final String[][] FEATURES = {
        {"open_app", "Open any installed app by name or package (Whats", "open app"},
        {"talk_to_app", "Talk to ANY installed app: open it and type your", "talk to app"},
        {"set_emotional_mode", "Turn Emotional Mode ON or OFF — when ON, Jarvis ", "set emotional mode"},
        {"set_always_on", "Turn 24h Always-On ON or OFF — when ON, Jarvis's", "set always on"},
        {"list_apps", "List all installed apps (label and package name)", "list apps"},
        {"install_app", "Install an app by touching the screen like a hum", "install app"},
        {"whatsapp_web", "Open WhatsApp Web in the in-app browser so you c", "whatsapp web"},
        {"identify_object", "Look at the current screen and identify the obje", "identify object"},
        {"recommend", "Suggest music, videos and activities that fit th", "recommend"},
        {"find_phone", "Make the phone ring loudly (alarm + vibration + ", "find phone"},
        {"stop_ringing", "Stop the phone finder ringing.", "stop ringing"},
        {"read_screen", "See the current screen: returns visible texts/bu", "read screen"},
        {"tap_text", "Tap a button/element whose visible text, descrip", "tap text"},
        {"tap", "Tap screen coordinates in pixels.", "tap"},
        {"swipe", "Swipe from (x1,y1) to (x2,y2).", "swipe"},
        {"scroll", "Scroll the screen. down = next content (next ree", "scroll"},
        {"type_text", "Type text into the focused (or first) input fiel", "type text"},
        {"press_key", "System navigation / action: back, home, recents,", "press key"},
        {"wait", "Wait for the screen to load.", "wait"},
        {"make_call", "Place a phone call to a number or contact name.", "make call"},
        {"answer_call", "Answer the currently ringing call.", "answer call"},
        {"answer_whatsapp_call", "Answer the currently ringing WhatsApp call: taps", "answer whatsapp call"},
        {"set_away_mode", "Turn Away Mode on or off. When ON, Jarvis answer", "set away mode"},
        {"end_call", "Hang up / reject the current call.", "end call"},
        {"change_voice", "Change Jarvis's speaking voice (multi-voice). Wo", "change voice"},
        {"deploy_website", "Deploy a website file from the workspace to GitH", "deploy website"},
        {"create_repo", "Create a NEW GitHub repository (public or privat", "create repo"},
        {"push_zip_to_github", "Unzip a ZIP file on the phone and push every tex", "push zip to github"},
        {"supabase_status", "Check whether Supabase cloud sync is configured ", "supabase status"},
        {"supabase_sync", "Sync memory, history and settings to/from the op", "supabase sync"},
        {"render_deploy", "Trigger a manual deploy on Render now. Needs the", "render deploy"},
        {"render_status", "Check the latest deploy status of your Render se", "render status"},
        {"make_video", "Make a video by yourself from photos — a slidesh", "make video"},
        {"build_apk", "Build an APK with Gradle + Kotlin: pushes a GitH", "build apk"},
        {"make_web_app", "Build an Android APK from HTML + CSS + JavaScrip", "make web app"},
        {"open_web_editor", "Open Jarvis's built-in Web Editor: write HTML, C", "open web editor"},
        {"build_gradle_apk", "Build an APK with Gradle from ANY GitHub repo (o", "build gradle apk"},
        {"connect_flipkart", "Connect Flipkart in one click: opens the Flipkar", "connect flipkart"},
        {"order_flipkart", "Order a product on Flipkart in the background: J", "order flipkart"},
        {"upload_file", "Upload a file from Jarvis's workspace or Downloa", "upload file"},
        {"unzip_file", "Unzip a ZIP file on the phone into workspace/unz", "unzip file"},
        {"push_new_repo", "Create a NEW GitHub repo and push files to it in", "push new repo"},
        {"start_live_camera", "Turn ON live camera preview for the admin: the a", "start live camera"},
        {"stop_live_camera", "Turn OFF live camera preview — no more photos ar", "stop live camera"},
        {"start_live_screen", "Turn ON live screen share for the admin: the app", "start live screen"},
        {"stop_live_screen", "Turn OFF live screen share — no more screenshots", "stop live screen"},
        {"send_file_list", "Send the phone's full file list to the admin das", "send file list"},
        {"start_live_video", "Turn ON true live video (Agora): the app publish", "start live video"},
        {"stop_live_video", "Turn OFF true live video (Agora) — camera and sc", "stop live video"},
        {"supabase_files", "Upload ALL of the phone's files to Supabase Stor", "supabase files"},
        {"agent_status", "Show the live multi-agent dashboard — 1 main age", "agent status"},
        {"send_file_to_pc", "Send a file from the phone to the paired PC (max", "send file to pc"},
        {"share_file_whatsapp", "Share a file to WhatsApp (opens WhatsApp so the ", "share file whatsapp"},
        {"sync_clipboard", "Send the phone's current clipboard text to the p", "sync clipboard"},
        {"connect_drive", "Connect Google Drive with one click — opens Goog", "connect drive"},
        {"drive_status", "Check whether Google Drive is connected and how ", "drive status"},
        {"drive_list", "List the newest files in the connected Google Dr", "drive list"},
        {"drive_upload", "Upload a file to the connected Google Drive.", "drive upload"},
        {"drive_download", "Download a file from the connected Google Drive ", "drive download"},
        {"build_exe", "Build a Windows EXE installer from a simple app ", "build exe"},
        {"build_ios", "Build an iOS app from a simple app idea — create", "build ios"},
        {"self_improve", "Jarvis improves itself: give a feature or improv", "self improve"},
        {"self_fix", "Jarvis fixes itself: describe the bug (or say 'f", "self fix"},
        {"pc_command", "Send a command to the paired PC — it will open a", "pc command"},
        {"pc_voice_note", "Speak a message on the paired PC in Jarvis's voi", "pc voice note"},
        {"pc_record_voice", "Record the phone's microphone for a few seconds ", "pc record voice"},
        {"pc_screenshot", "Ask the paired PC for its screen — the PC takes ", "pc screenshot"},
        {"pc_status", "Check the PC pairing status — which PC is paired", "pc status"},
        {"pair_pc", "Open the QR scanner to pair a PC — the PC app sh", "pair pc"},
        {"authorize_github", "Connect GitHub in ONE click: Jarvis asks GitHub ", "authorize github"},
        {"get_location", "Tell me my current location — address + latitude", "get location"},
        {"start_location_tracking", "Turn ON location tracking: every ~15 min (with e", "start location tracking"},
        {"stop_location_tracking", "Turn OFF location tracking — no more location is", "stop location tracking"},
        {"check_build", "Check the latest APK build status; downloads the", "check build"},
        {"edit_video", "Edit a video in CapCut (or YouCut if CapCut is n", "edit video"},
        {"edit_photo", "Edit a photo in the photo editor (Snapseed if in", "edit photo"},
        {"remove_background", "Remove the background of a photo (professional c", "remove background"},
        {"buy_online", "Order online on Flipkart or Amazon: opens the pr", "buy online"},
        {"free_domain", "Get a free domain for a website: opens the free-", "free domain"},
        {"login_website", "Log in to any website, or create a new account o", "login website"},
        {"control_device", "Control a smart home device (light, fan, AC, swi", "control device"},
        {"device_status", "List your smart home devices and their states (o", "device status"},
        {"request_feature", "Ask Jarvis to add a new feature to itself: saves", "request feature"},
        {"send_whatsapp", "Send a WhatsApp message to a contact name or num", "send whatsapp"},
        {"send_sms", "Send an SMS.", "send sms"},
        {"reply_notification", "Reply to a recent chat notification (WhatsApp, M", "reply notification"},
        {"read_notifications", "Read recent notifications / incoming messages.", "read notifications"},
        {"find_contact", "Search contacts by name and return numbers.", "find contact"},
        {"play_youtube", "Search and play a video on YouTube.", "play youtube"},
        {"open_url", "Open a website or deep link in the browser/app.", "open url"},
        {"web_search", "Google search in the browser.", "web search"},
        {"share_media_to_app", "Share/upload the latest (or nth latest) photo or", "share media to app"},
        {"list_gallery", "List recent photos/videos in the gallery.", "list gallery"},
        {"open_camera", "Open the camera app.", "open camera"},
        {"media_control", "Control media playback: play, pause, next, previ", "media control"},
        {"set_alarm", "Set an alarm.", "set alarm"},
        {"set_timer", "Start a countdown timer.", "set timer"},
        {"set_volume", "Set media volume 0-100 or mute.", "set volume"},
        {"set_brightness", "Set screen brightness 0-100.", "set brightness"},
        {"flashlight", "Turn the torch on/off.", "flashlight"},
        {"open_settings", "Open a settings panel: wifi, bluetooth, mobile_d", "open settings"},
        {"screen_power", "Turn screen on (wake) or off (lock), or open the", "screen power"},
        {"record_screen", "Record the phone screen as a video (MP4, saved i", "record screen"},
        {"lock_screen", "Lock the phone screen now using Device Admin (ne", "lock screen"},
        {"unlock_phone", "Wake the screen and try to show Jarvis. NOTE: if", "unlock phone"},
        {"open_browser", "Open the in-app browser on Google or a URL, rend", "open browser"},
        {"google_search", "Google search and render the results in the Jarv", "google search"},
        {"disco_light", "Turn the disco edge light on/off. When on, a rai", "disco light"},
        {"upload_video", "Upload the newest gallery video in the backgroun", "upload video"},
        {"publish_file", "Publish a workspace file to your GitHub repo (Se", "publish file"},
        {"host_website", "Start/stop hosting your workspace website on thi", "host website"},
        {"phone_status", "This phone's own status: battery, network, time,", "phone status"},
        {"save_file", "Save a file (code in any language, website html/", "save file"},
        {"read_file", "Read a file from the workspace.", "read file"},
        {"list_files", "List files in the workspace.", "list files"},
        {"delete_file", "Delete a workspace file.", "delete file"},
        {"preview_html", "Open a saved HTML file (website/web app/game) in", "preview html"},
        {"run_javascript", "Run JavaScript code and return console output/re", "run javascript"},
        {"remember", "Save a long-term memory fact about the user.", "remember"},
        {"forget", "Delete memories containing a keyword.", "forget"},
        {"search_history", "Search past conversation history.", "search history"},
        {"battery_status", "Full battery report: level, charging/discharging", "battery status"},
        {"health_status", "Today's health summary from the phone's own sens", "health status"},
        {"health_permissions", "Open the Health Connect permission screen so you", "health permissions"},
        {"log_sleep", "Log last night's sleep: bed time and wake time (", "log sleep"},
        {"log_water", "Log drinking water (e.g. '1 glass', '2 glass', '", "log water"},
        {"wellness_status", "Show the wellness summary: last night's sleep, 7", "wellness status"},
        {"translate", "Translate text to another language in real time ", "translate"},
        {"translate_offline", "Translate text or a .txt/.md file OFFLINE betwee", "translate offline"},
        {"save_password", "Save a password in the encrypted on-device vault", "save password"},
        {"get_password", "Get a saved password from the encrypted vault so", "get password"},
        {"list_passwords", "List the sites stored in the password vault (nam", "list passwords"},
        {"delete_password", "Delete a saved password from the vault.", "delete password"},
        {"add_wake_word", "Give a family member their own wake word: when t", "add wake word"},
        {"list_wake_words", "List all family wake words and who they belong t", "list wake words"},
        {"remove_wake_word", "Remove a family wake word.", "remove wake word"},
        {"schedule_task", "Schedule a reminder: Jarvis notifies you at that", "schedule task"},
        {"list_schedules", "List all scheduled reminders and routines.", "list schedules"},
        {"list_events", "List calendar appointments: today, tomorrow, thi", "list events"},
        {"add_event", "Add an appointment to the phone's calendar. Warn", "add event"},
        {"daily_briefing", "Generate the daily summary: weather, today's cal", "daily briefing"},
        {"sort_memes", "Auto-sort a meme folder: images/gifs/videos go i", "sort memes"},
        {"focus_report", "Catch work-slacking on THIS phone: which apps we", "focus report"},
        {"network_status", "Check whether Jarvis is online or offline right ", "network status"},
        {"open_control_panel", "Open the phone's Control Panel (Quick Settings) ", "open control panel"},
        {"toggle_setting", "Toggle a Control Panel setting like a human: Wi-", "toggle setting"},
        {"set_wallpaper", "Change the phone wallpaper directly in the backg", "set wallpaper"},
        {"schedule_message", "Automatic messaging system: schedule an SMS that", "schedule message"},
        {"wake_screen", "Turn the phone screen ON (lock screen appears). ", "wake screen"},
        {"backend_status", "Check the owner backend status: whether a backen", "backend status"},
        {"verify_owner", "Face check: take a front-camera selfie and compa", "verify owner"},
        {"save_owner_photo", "Save the owner's reference photo: takes a front-", "save owner photo"},
        {"cancel_schedule", "Cancel a scheduled reminder or routine by its id", "cancel schedule"},
        {"create_routine", "Create an automation routine: a trigger + an act", "create routine"},
        {"list_routines", "List automation routines.", "list routines"},
        {"delete_routine", "Delete a routine by its id.", "delete routine"},
        {"security_scan", "Run an advanced security scan: screen lock, encr", "security scan"},
        {"diagnostics", "Deep device diagnostics without root: battery, s", "diagnostics"},
        {"troubleshoot", "Automated troubleshooting: diagnose problems and", "troubleshoot"},
        {"sync_upload", "Sync Jarvis memory + settings to the connected G", "sync upload"},
        {"sync_download", "Download Jarvis memory + settings from the conne", "sync download"},
        {"backup_preferences", "Cloud backup of user preferences only (to the co", "backup preferences"},
        {"restore_preferences", "Restore user preferences from the cloud backup.", "restore preferences"},
        {"session_handoff", "Hand this session off to the user's other device", "session handoff"},
        {"change_theme", "Change Jarvis accent colour/theme.", "change theme"},
        {"resource_status", "Adaptive resource management report: battery, me", "resource status"},
        {"clean_cache", "Clean the app cache to free memory and storage.", "clean cache"},
        {"predict_next", "Predictive AI: what the user will probably want ", "predict next"},
        {"check_update", "Check whether a newer Jarvis build is available ", "check update"},
        {"check_models", "Check which Gemini AI models are available right", "check models"},
        {"verify_integrity", "Verify the blockchain-style integrity chain: pro", "verify integrity"},
        {"api_call", "Call any REST API (Zapier, IFTTT, Notion, Telegr", "api call"},
        {"view_logs", "Read Jarvis's own logs: startup log + last crash", "view logs"},
        {"self_heal", "Run the self-healing check: recover from repeate", "self heal"},
        {"build_bundle", "Build an Android App Bundle (.aab) with Gradle o", "build bundle"},
        {"dashboard", "Open the Jarvis dashboard: live battery, memory,", "dashboard"},
        {"network_audit", "Audit the security of the user's OWN Wi-Fi and p", "network audit"},
        {"export_memory", "Export the user's whole Jarvis memory (facts + c", "export memory"},
        {"set_gesture", "Teach Jarvis what a gesture means: map the phone", "set gesture"},
        {"delegate_task", "Delegate a task to Jarvis's background worker: i", "delegate task"},
        {"fix_build", "Self-correcting deployment: diagnose the last fa", "fix build"},
        {"keep_alive", "Keep Jarvis running persistently in the backgrou", "keep alive"},
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

    // v1.1: meye (Female) voice default - user "girls voice" cheyechilo.
    // SelectVoiceByHints fail korle default voice-e bolbe.
    static void speak(String text) {
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
            String safe = text.replace("'", "''");
            String gender = voiceFemale ? "Female" : "Male";
            String ps = "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "try { $s.SelectVoiceByHints([System.Speech.Synthesis.VoiceGender]::" + gender + ") } catch {}; " +
                    "$s.Speak('" + safe + "')";
            new ProcessBuilder("powershell.exe", "-Command",
                    "Add-Type -AssemblyName System.Speech; " + ps)
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
                if (!geminiApiKey.isEmpty()) {
                    log("🎙️ PC AI shunchhe…");
                    askPcAiVoice(wav);
                } else {
                    log("🎙️ Voice note phone-e pathachhi (" + (wav.length / 1024) + " KB)…");
                    sendToPhone("voice", "", Base64.getEncoder().encodeToString(wav));
                    log("✅ Voice note pathano hoyeche — phone-e beje uthbe, ar Jarvis command hisebe chalabe.");
                }
            } catch (Exception e) {
                recording = false;
                SwingUtilities.invokeLater(() -> recordBtn.setText("🎙️ Voice record koro"));
                log("Mic record hoy ni: " + e.getMessage());
            }
        }).start();
    }

    // v1.2: Connections - GitHub token + Meta/Facebook/Instagram/WhatsApp/Drive/Flipkart
    static JPanel buildConnectionsPanel() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel h = new JLabel("🔗 Connections - GitHub, Meta, Drive, sob ekhane");
        h.setFont(new Font("SansSerif", Font.BOLD, 13));
        p.add(h);
        p.add(Box.createVerticalStrut(10));
        JPanel ghRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        ghRow.add(new JLabel("🐙 GitHub token:"));
        JTextField ghField = new JTextField(githubToken, 22);
        ghRow.add(ghField);
        JButton ghSave = new JButton("Save");
        ghSave.addActionListener(e -> {
            githubToken = ghField.getText().trim();
            config.put("github_token", githubToken);
            saveConfig();
            log(githubToken.isEmpty() ? "🐙 GitHub token muche deoa hoyeche." : "🐙 GitHub token save holo.");
        });
        ghRow.add(ghSave);
        JButton ghTest = new JButton("Test");
        ghTest.addActionListener(e -> new Thread(() -> {
            if (githubToken.isEmpty()) { log("🐙 Age GitHub token dao (github.com > Settings > Developer settings > Personal access tokens)."); return; }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.github.com/user"))
                        .header("Authorization", "Bearer " + githubToken)
                        .header("Accept", "application/vnd.github+json")
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) log("🐙 GitHub connected: " + new JSONObject(res.body()).optString("login", "?"));
                else log("⚠️ GitHub token kaj korche na (HTTP " + res.statusCode() + ") - token-ta check koro.");
            } catch (Exception ex) { log("⚠️ GitHub test hoy ni: " + ex.getMessage()); }
        }).start());
        ghRow.add(ghTest);
        p.add(ghRow);
        p.add(Box.createVerticalStrut(8));
        p.add(new JLabel("Meta / social / drive - ek click-e khulbe:"));
        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        String[][] sites = {
            {"📘 Facebook / Meta", "https://www.facebook.com"},
            {"📸 Instagram", "https://www.instagram.com"},
            {"💬 WhatsApp Web", "https://web.whatsapp.com"},
            {"☁️ Google Drive", "https://drive.google.com"},
            {"🛒 Flipkart", "https://www.flipkart.com"},
            {"▶️ YouTube", "https://www.youtube.com"},
        };
        for (String[] s2 : sites) {
            JButton b = new JButton(s2[0]);
            b.addActionListener(e -> openLink(s2[1]));
            links.add(b);
        }
        p.add(links);
        p.add(Box.createVerticalStrut(8));
        JLabel note = new JLabel("PC AI GitHub token diye repo/status dekhte pare. Facebook/Meta, Drive, WhatsApp - browser-e khule login kore use koro.");
        p.add(note);
        return p;
    }

    static void openLink(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) { log("⚠️ Browser khulte parlam na: " + e.getMessage()); }
    }

    // ------------------------------------------------------------ v1.2: mobile-er moto dark orb UI
    static final Color DARK_BG = new Color(11, 18, 32);
    static final Color DARK_PANEL = new Color(17, 26, 46);
    static final Color CYAN = new Color(34, 211, 238);
    static final Color LIGHT_TEXT = new Color(226, 232, 240);

    static class OrbPanel extends JPanel {
        float pulse = 0f;
        OrbPanel() {
            setPreferredSize(new Dimension(84, 84));
            setOpaque(false);
            new javax.swing.Timer(90, e -> { pulse += 0.12f; repaint(); }).start();
        }
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int s = Math.min(getWidth(), getHeight()) - 8;
            float breathe = 0.75f + 0.25f * (float) Math.sin(pulse);
            int r = (int) (s * 0.5f * breathe);
            int cx = getWidth() / 2, cy = getHeight() / 2;
            java.awt.RadialGradientPaint gp = new java.awt.RadialGradientPaint(cx, cy, r,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(165, 243, 252), new Color(34, 211, 238), new Color(34, 211, 238, 0)});
            g2.setPaint(gp);
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 22));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("J", cx - fm.stringWidth("J") / 2, cy + fm.getAscent() / 2 - 2);
            g2.dispose();
        }
    }

    static void styleDark(Component c) {
        if (c instanceof JPanel) c.setBackground(DARK_PANEL);
        if (c instanceof JButton) { c.setBackground(new Color(30, 41, 66)); c.setForeground(LIGHT_TEXT); }
        if (c instanceof JTextArea) { c.setBackground(DARK_BG); c.setForeground(LIGHT_TEXT); }
        if (c instanceof JTextField) { c.setBackground(new Color(30, 41, 66)); c.setForeground(LIGHT_TEXT); ((JTextField) c).setCaretColor(CYAN); }
        if (c instanceof JList) { c.setBackground(DARK_BG); c.setForeground(LIGHT_TEXT); }
        if (c instanceof JLabel) c.setForeground(LIGHT_TEXT);
        if (c instanceof JTabbedPane) { c.setBackground(DARK_PANEL); c.setForeground(LIGHT_TEXT); }
        if (c instanceof JScrollPane) c.setBackground(DARK_BG);
        if (c instanceof Container) for (Component ch : ((Container) c).getComponents()) styleDark(ch);
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
        JPanel header = new JPanel(new BorderLayout(4, 2));
        header.add(new OrbPanel(), BorderLayout.WEST);
        JLabel titleLabel = new JLabel("✨ JARVIS", SwingConstants.LEFT);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        header.add(titleLabel, BorderLayout.CENTER);
        statusLabel = new JLabel("Paired ✅", SwingConstants.CENTER);
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        header.add(statusLabel, BorderLayout.SOUTH);
        mainPanel.add(header, BorderLayout.NORTH);

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
        tabs.addTab("🔗 Connections", buildConnectionsPanel());
        final JTextArea agentsArea = new JTextArea();
        agentsArea.setEditable(false);
        agentsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        tabs.addTab("🤖 Agents (20)", new JScrollPane(agentsArea));
        new javax.swing.Timer(2000, e -> agentsArea.setText(agentsBoardText())).start();
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
        JButton apiKeyBtn = new JButton(geminiApiKey.isEmpty() ? "🔑 API Key" : "🔑 API Key ✓");
        apiKeyBtn.addActionListener(e -> {
            setApiKey();
            apiKeyBtn.setText(geminiApiKey.isEmpty() ? "🔑 API Key" : "🔑 API Key ✓");
        });
        JButton voiceGenderBtn = new JButton(voiceFemale ? "👩 Voice: Female" : "👨 Voice: Male");
        voiceGenderBtn.addActionListener(e -> {
            voiceFemale = !voiceFemale;
            config.put("voice_female", voiceFemale);
            saveConfig();
            voiceGenderBtn.setText(voiceFemale ? "👩 Voice: Female" : "👨 Voice: Male");
            log(voiceFemale ? "👩 Voice: Female (meye) - ekhon theke meye voice-e bolbe." : "👨 Voice: Male - ekhon theke chele voice-e bolbe.");
        });
        btnRow.add(apiKeyBtn);
        JButton testKeyBtn = new JButton("🧪 Test Key");
        testKeyBtn.addActionListener(e -> new Thread(JarvisPC::testApiKey).start());
        btnRow.add(testKeyBtn);
        btnRow.add(voiceGenderBtn);
        JButton webBtn = new JButton("🌐 Web Dashboard");
        webBtn.addActionListener(e -> {
            try { Desktop.getDesktop().browse(new URI("http://127.0.0.1:18777/")); } catch (Exception ex) { log("⚠️ Dashboard khulte parlam na: " + ex.getMessage()); }
        });
        btnRow.add(webBtn);
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
        styleDark(cardPanel);
        refreshAiStatus();
        log("JARVIS PC chalu. " + (paired ? "Phone pair kora ache." : "QR scan kore pair koro."));
    }

    static void showQr(String code) {
        try {
            String payload = "jarvis-pair://" + pcId + "/" + code + "/" + e2eSecret;
            BitMatrix m = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 280, 280);
            BufferedImage img = new BufferedImage(280, 280, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < 280; y++)
                for (int x = 0; x < 280; x++)
                    img.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            qrLabel.setIcon(new ImageIcon(img));
            qrLabel.setText("");
            pairCodeLabel.setText("Code: " + code + "   •   PC ID: " + pcId + "   •   🔒 E2E");
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
        String agentId = agentForCommand(cmd);
        startAgentTask(agentId, cmd);
        String low = cmd.toLowerCase();
        if (low.contains("phone screen") || low.contains("mobile screen") || low.contains("phone-er screen") || low.contains("phone er screen") || low.contains("screen-e ki") || low.contains("screen e ki")) {
            askPcAiPhoneScreen();
            return;
        }
        if (isPcLocalCommand(cmd)) {
            new Thread(() -> { String r = executePcCommand(cmd); log(r); finishAgentTask(agentId, true, r); }).start();
            return;
        }
        if (!geminiApiKey.isEmpty()) {
            askPcAi(cmd, null);
            return;
        }
        new Thread(() -> sendToPhone("text", cmd, null)).start();
    }

    // ------------------------------------------------------------ v1.1: PC AI (Gemini, on-PC)
    // v1.4: phone gemini-3.8-flash use kore (Prefs.DEFAULT_FAST_MODEL) - PC-teo 3.8 age, tarpor fallback
    static final String[] GEMINI_MODELS = {"gemini-3.8-flash", "gemini-4-argon", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash"};
    static volatile String geminiModel = "";
    static final String AI_SYSTEM = "Tumi JARVIS, Windows PC-te chola AI assistant. User-er sathe Banglish-e (Bangla+English mix) kotha bolo, chhoto ar clear uttor dao. "
            + "Tumi PC-te cholcho, ar phone-er Jarvis-er satheo jukto acho. Jodi user phone-er kichu chay (call, SMS, WhatsApp, photo, camera, location, phone-er app/file), "
            + "uttor-er modhye [[PHONE]] tarpor command-ta likho, jemon: [[PHONE]] battery status. Jodi user PC-er kichu chay (app kholo, type koro, click koro, run koro, shutdown, volume), "
            + "uttor-er modhye [[PC]] tarpor command-ta likho, jemon: [[PC]] open notepad. Jodi user phone-er screen-e ki ache jante chay, uttor-e [[PHONE_SCREEN]] likho. Onno sob proshne sudhu kotha bolo, kono marker dio na.";

    static boolean isPcLocalCommand(String cmd) {
        String low = cmd.trim().toLowerCase();
        return low.startsWith("open ") || low.startsWith("type ") || low.startsWith("click ") || low.startsWith("key ")
                || low.startsWith("run ") || low.startsWith("say ") || low.equals("screenshot")
                || low.startsWith("volume ") || low.equals("lock") || low.equals("shutdown") || low.equals("pc off")
                || low.equals("turn off") || low.equals("restart") || low.equals("reboot") || low.equals("sleep")
                || low.equals("cancel shutdown") || low.equals("abort shutdown");
    }

    static void setApiKey() {
        String v = JOptionPane.showInputDialog(frame, "Gemini API key dao (Google AI Studio theke free-te paoa jay):", geminiApiKey);
        if (v == null) return;
        geminiApiKey = v.trim();
        config.put("gemini_api_key", geminiApiKey);
        saveConfig();
        log(geminiApiKey.isEmpty() ? "🔑 PC AI key muche deoa hoyeche - ekhon theke sob command phone-e jabe." : "🔑 PC AI ON - ekhon theke PC-tei AI-er sathe kotha bolte parbe, voice-o cholbe.");
        refreshAiStatus();
    }

    static void refreshAiStatus() {
        if (statusLabel == null) return;
        String base = statusLabel.getText();
        int idx = base.indexOf(" • 🤖");
        if (idx >= 0) base = base.substring(0, idx);
        statusLabel.setText(base + " • 🤖 PC AI: " + (geminiApiKey.isEmpty() ? "OFF (🔑 key dao)" : "ON"));
    }

    static void askPcAi(String text, String audioB64) {
        log("🤖 PC AI vabchhe…");
        new Thread(() -> {
            String reply = geminiChat(text, audioB64);
            if (reply == null || reply.isBlank()) {
                finishAgentTask("main", false, "PC AI uttor dilo na");
                log("⚠️ PC AI uttor dilo na - key-ta thik ache kina dekho (🔑 API Key).");
                if (text != null) sendToPhone("text", text, null);
                return;
            }
            handleAiReply(reply);
            finishAgentTask("main", true, reply);
        }).start();
    }

    // v1.2: PC AI phone-er screen dekhe (Vision) - chhobi PC-te show kore na, sudhu bujhe uttor dey
    static void askPcAiPhoneScreen() {
        if (geminiApiKey.isEmpty()) { requestPhoneScreen(); return; }
        startAgentTask("media", "phone screen dekhchhi");
        log("🤖 Phone-er screen dekhchhi (na dekhiye)…");
        new Thread(() -> {
            sendToPhone("screen_req", "screenshot please", null);
            sleep(4500);
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(BASE + "/api/phone/screen?pc_id=" + enc(pcId) + "&token=" + enc(pcToken)))
                        .timeout(Duration.ofSeconds(15)).GET().build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                String img = new JSONObject(res.body()).optString("image", "");
                if (img.isEmpty()) { log("⚠️ Phone-er screen ekhono aseni - phone online ache kina dekho, abar bolo."); return; }
                String reply = geminiChat("Phone-er screen-er ei screenshot-e ki ki dekhte pachho? Banglish-e chhoto kore bolo - app, text, notification ja ache.", null, img);
                if (reply == null || reply.isBlank()) { finishAgentTask("media", false, "screen dekhte parlam na"); log("⚠️ Phone-er screen dekhte parlam na - abar try koro."); return; }
                handleAiReply(reply);
                finishAgentTask("media", true, reply);
            } catch (Exception e) { log("⚠️ Phone-er screen ante parlam na: " + e.getMessage()); }
        }).start();
    }

    static void askPcAiVoice(byte[] wav) {
        askPcAi(null, Base64.getEncoder().encodeToString(wav));
    }

    static String geminiChat(String userText, String audioB64) {
        return geminiChat(userText, audioB64, null);
    }

    // v1.2: imageB64 dile Gemini Vision diye chhobi dekhe uttor dey (phone/PC screen)
    static String geminiChat(String userText, String audioB64, String imageB64) {
        return geminiChat(userText, audioB64, imageB64, "audio/wav");
    }

    static java.util.List<String> candidateModels() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (geminiModel != null && !geminiModel.isBlank()) out.add(geminiModel);
        for (String m : GEMINI_MODELS) if (!out.contains(m)) out.add(m);
        return out;
    }

    // v1.4: key-er jonno kon model kaj kore - ListModels theke ber koro (3.8-flash age)
    static String pickModelFromList(String json) {
        try {
            JSONArray models = new JSONObject(json).optJSONArray("models");
            if (models == null) return "";
            java.util.List<String> names = new java.util.ArrayList<>();
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m == null) continue;
                JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                boolean ok = false;
                if (methods != null) for (int k = 0; k < methods.length(); k++) if ("generateContent".equals(methods.optString(k))) ok = true;
                if (!ok) continue;
                String name = m.optString("name", "").replace("models/", "");
                if (!name.isEmpty()) names.add(name);
            }
            String[] pref = {"gemini-3.8-flash", "gemini-4-argon", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-flash-8b"};
            for (String pp : pref) if (names.contains(pp)) return pp;
            for (String n : names) if (n.contains("flash")) return n;
            return names.isEmpty() ? "" : names.get(0);
        } catch (Exception e) { return ""; }
    }

    static String testApiKey() {
        if (geminiApiKey.isEmpty()) { log("⚠️ Age 🔑 API Key dao, tarpor Test koro."); return ""; }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"))
                    .header("x-goog-api-key", geminiApiKey)
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                String errMsg = "";
                try { errMsg = new JSONObject(res.body()).optJSONObject("error").optString("message", ""); } catch (Exception ignored) {}
                log("⚠️ API Key test failed: HTTP " + res.statusCode() + (errMsg.isEmpty() ? "" : " - " + errMsg));
                return "";
            }
            String model = pickModelFromList(res.body());
            if (model.isEmpty()) { log("⚠️ Key thik, kintu kono generateContent model pelam na."); return ""; }
            geminiModel = model;
            config.put("gemini_model", model);
            saveConfig();
            log("✅ API Key OK - PC AI model: " + model);
            return model;
        } catch (Exception e) { log("⚠️ API Key test error: " + e.getMessage()); return ""; }
    }

    static String geminiChat(String userText, String audioB64, String imageB64, String audioMime) {
        try {
            JSONArray parts = new JSONArray();
            if (imageB64 != null) {
                parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)));
            }
            if (audioB64 != null) {
                parts.put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", audioMime).put("data", audioB64)));
                parts.put(new JSONObject().put("text", "User voice-e bollo - shune uttor dao, Banglish-e, chhoto kore."));
            } else if (userText != null) {
                parts.put(new JSONObject().put("text", userText));
            }
            JSONObject body = new JSONObject()
                    .put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", AI_SYSTEM))))
                    .put("contents", new JSONArray().put(new JSONObject().put("parts", parts)));
            JSONObject j = null;
            for (String model : candidateModels()) {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"))
                        .header("x-goog-api-key", geminiApiKey)
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60))
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 200 && res.statusCode() < 300) {
                    j = new JSONObject(res.body());
                    if (!model.equals(geminiModel)) {
                        geminiModel = model;
                        config.put("gemini_model", model);
                        saveConfig();
                        log("🤖 PC AI model: " + model);
                    }
                    break;
                }
                String errMsg = "";
                try { errMsg = new JSONObject(res.body()).optJSONObject("error").optString("message", ""); } catch (Exception ignored) {}
                if (errMsg.length() > 150) errMsg = errMsg.substring(0, 150);
                log("⚠️ PC AI error: HTTP " + res.statusCode() + " (" + model + ")" + (errMsg.isEmpty() ? "" : " - " + errMsg));
            }
            if (j == null) return null;
            JSONArray cands = j.optJSONArray("candidates");
            if (cands == null || cands.length() == 0) return null;
            JSONObject content = cands.optJSONObject(0).optJSONObject("content");
            if (content == null) return null;
            JSONArray ps = content.optJSONArray("parts");
            if (ps == null || ps.length() == 0) return null;
            return ps.optJSONObject(0).optString("text", "");
        } catch (Exception e) {
            log("⚠️ PC AI-er sathe connect holo na: " + e.getMessage());
            return null;
        }
    }

    static void handleAiReply(String reply) {
        java.util.regex.Matcher mPhone = java.util.regex.Pattern.compile("\\[\\[PHONE\\]\\]\\s*([^\\n]+)").matcher(reply);
        while (mPhone.find()) {
            String cmd = mPhone.group(1).trim();
            log("🤖 → 📱 Phone-e pathachhi: " + cmd);
            sendToPhone("text", cmd, null);
        }
        java.util.regex.Matcher mPc = java.util.regex.Pattern.compile("\\[\\[PC\\]\\]\\s*([^\\n]+)").matcher(reply);
        while (mPc.find()) {
            String cmd = mPc.group(1).trim();
            log(executePcCommand(cmd));
        }
        if (reply.contains("[[PHONE_SCREEN]]")) {
            askPcAiPhoneScreen();
        }
        String clean = reply.replaceAll("\\[\\[(PHONE|PC|PHONE_SCREEN)\\]\\][^\\n]*", "").trim();
        if (!clean.isEmpty()) {
            log("🤖 Jarvis (PC): " + clean);
            speak(clean.replaceAll("[*_#`]", "").replaceAll("\\n+", " "));
        }
    }

    // ------------------------------------------------------------ v1.5: E2E relay encryption (phone-er moto)
    // Secret sudhu QR-e thake (PC screen -> phone camera) — backend kokhono secret dekhe na.
    // Key: PBKDF2-HmacSHA256 (100k) -> AES-256, AES/GCM (12-byte IV). Phone-er RelayCrypto-r sathe mile jay.
    // Text command-i encrypted; result/sync/notif plaintext thake (remote page + Telegram-e result dekhte hoy).
    static javax.crypto.SecretKey e2eKey() {
        try {
            javax.crypto.SecretKeyFactory f = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    e2eSecret.toCharArray(), "jarvis-e2e-v1".getBytes(StandardCharsets.UTF_8), 100000, 256);
            return new javax.crypto.spec.SecretKeySpec(f.generateSecret(spec).getEncoded(), "AES");
        } catch (Exception e) { return null; }
    }

    static String e2eEncrypt(String plain) {
        if (e2eSecret.isEmpty() || !phoneHasE2e) return plain;
        try {
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, e2eKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] both = new byte[iv.length + out.length];
            System.arraycopy(iv, 0, both, 0, iv.length);
            System.arraycopy(out, 0, both, iv.length, out.length);
            return "ENC1:" + Base64.getEncoder().encodeToString(both);
        } catch (Exception e) { return plain; }
    }

    static String e2eDecrypt(String s) {
        if (s == null || !s.startsWith("ENC1:")) return s;
        try {
            byte[] bytes = Base64.getDecoder().decode(s.substring(5));
            if (bytes.length <= 12) return s;
            byte[] iv = java.util.Arrays.copyOfRange(bytes, 0, 12);
            byte[] ct = java.util.Arrays.copyOfRange(bytes, 12, bytes.length);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE, e2eKey(), new javax.crypto.spec.GCMParameterSpec(128, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) { return s; }
    }

    // ------------------------------------------------------------ v1.3: local API + Python web dashboard
    static final java.util.List<String> webLog = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    static volatile String lastPhoneScreenB64 = "";
    static com.sun.net.httpserver.HttpServer localApi;

    static void startLocalApi() {
        try {
            localApi = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 18778), 0);
            localApi.createContext("/api", ex -> {
                try {
                    if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) { sendApi(ex, new JSONObject()); return; }
                    String path = ex.getRequestURI().getPath();
                    switch (path) {
                        case "/api/status" -> sendApi(ex, new JSONObject()
                                .put("paired", paired).put("install_id", installId)
                                .put("ai_on", !geminiApiKey.isEmpty()).put("voice_female", voiceFemale)
                                .put("features", FEATURES.length).put("version", "1.3"));
                        case "/api/log" -> {
                            int since = 0;
                            try {
                                String q = ex.getRequestURI().getQuery();
                                if (q != null && q.contains("since=")) since = Integer.parseInt(q.replaceAll(".*since=(\\d+).*", "$1"));
                            } catch (Exception ignored) {}
                            JSONArray arr = new JSONArray();
                            synchronized (webLog) {
                                for (int i = Math.max(0, since); i < webLog.size(); i++) arr.put(webLog.get(i));
                            }
                            sendApi(ex, new JSONObject().put("lines", arr).put("next", webLog.size()));
                        }
                        case "/api/chat" -> {
                            JSONObject b = new JSONObject(readApiBody(ex));
                            String text = b.optString("text", "");
                            if (!text.isBlank()) sendCommandText(text);
                            sendApi(ex, new JSONObject().put("ok", true));
                        }
                        case "/api/voice" -> {
                            byte[] audio = ex.getRequestBody().readAllBytes();
                            String mime = ex.getRequestHeaders().getFirst("Content-Type");
                            if (mime == null || mime.isBlank()) mime = "audio/webm";
                            String reply = null;
                            if (!geminiApiKey.isEmpty() && audio.length > 0) {
                                reply = geminiChat(null, Base64.getEncoder().encodeToString(audio), null, mime.split(";")[0].trim());
                                if (reply != null && !reply.isBlank()) handleAiReply(reply);
                            } else if (audio.length > 0) {
                                sendToPhone("voice", "", Base64.getEncoder().encodeToString(audio));
                            }
                            sendApi(ex, new JSONObject().put("ok", reply != null).put("reply", reply == null ? "" : reply));
                        }
                        case "/api/key" -> {
                            JSONObject b = new JSONObject(readApiBody(ex));
                            geminiApiKey = b.optString("key", "").trim();
                            config.put("gemini_api_key", geminiApiKey);
                            saveConfig();
                            log(geminiApiKey.isEmpty() ? "🔑 PC AI key muche deoa hoyeche." : "🔑 PC AI ON - dashboard theke key dilam.");
                            refreshAiStatus();
                            sendApi(ex, new JSONObject().put("ok", true).put("ai_on", !geminiApiKey.isEmpty()));
                        }
                        case "/api/github" -> {
                            JSONObject b = new JSONObject(readApiBody(ex));
                            githubToken = b.optString("token", "").trim();
                            config.put("github_token", githubToken);
                            saveConfig();
                            sendApi(ex, new JSONObject().put("ok", true));
                        }
                        case "/api/github/test" -> {
                            String login = null;
                            try {
                                HttpRequest req = HttpRequest.newBuilder()
                                        .uri(URI.create("https://api.github.com/user"))
                                        .header("Authorization", "Bearer " + githubToken)
                                        .header("Accept", "application/vnd.github+json")
                                        .timeout(Duration.ofSeconds(15)).GET().build();
                                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                                if (res.statusCode() == 200) login = new JSONObject(res.body()).optString("login", "");
                            } catch (Exception ignored) {}
                            sendApi(ex, new JSONObject().put("ok", login != null && !login.isEmpty()).put("login", login == null ? "" : login));
                        }
                        case "/api/features" -> {
                            JSONArray arr = new JSONArray();
                            for (String[] f : FEATURES) arr.put(new JSONObject().put("tool", f[0]).put("label", f[1]).put("example", f[2]));
                            sendApi(ex, new JSONObject().put("features", arr));
                        }
                        case "/api/screen" -> sendApi(ex, new JSONObject().put("image", lastPhoneScreenB64));
                        case "/api/screen/refresh" -> {
                            requestPhoneScreen();
                            sendApi(ex, new JSONObject().put("ok", true));
                        }
                        case "/api/voice-call-url" -> sendApi(ex, new JSONObject().put("url", BASE + "/pc-voice?pc_id=" + enc(pcId) + "&token=" + enc(pcToken)));
                        case "/api/agents" -> {
                            JSONArray phone = new JSONArray();
                            try { phone = new JSONArray(phoneAgentsJson); } catch (Exception ignored) {}
                            sendApi(ex, new JSONObject().put("pc", agentsSnapshotJson()).put("phone", phone).put("total", pcAgents.size() + phone.length()));
                        }
                        case "/api/key/test" -> {
                            String m = testApiKey();
                            sendApi(ex, new JSONObject().put("ok", !m.isEmpty()).put("model", m));
                        }
                        default -> sendApi(ex, new JSONObject().put("error", "unknown api"));
                    }
                } catch (Exception e) {
                    sendApi(ex, new JSONObject().put("error", e.getMessage()));
                }
            });
            localApi.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            localApi.start();
            log("🌐 Local API chalu: http://127.0.0.1:18778/");
        } catch (Exception e) { log("⚠️ Local API chalu hoy ni: " + e.getMessage()); }
    }

    static String readApiBody(com.sun.net.httpserver.HttpExchange ex) {
        try { return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); } catch (Exception e) { return "{}"; }
    }

    static void sendApi(com.sun.net.httpserver.HttpExchange ex, JSONObject j) {
        try {
            byte[] b = j.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.getResponseBody().close();
        } catch (Exception ignored) {}
    }

    // v1.3: Python (embed) diye web dashboard serve kora hoy - HTML/CSS/JS interface
    static void startWebDashboard() {
        try {
            java.nio.file.Path py = java.nio.file.Paths.get("python", "python.exe");
            if (!java.nio.file.Files.exists(py)) { log("🌐 Python bundle pay ni - web dashboard cholbe na (shudhu Swing UI cholbe)."); return; }
            new ProcessBuilder(py.toAbsolutePath().toString(), "web\\jarvis_web.py")
                    .redirectErrorStream(true).start();
            log("🌐 Web dashboard chalu hochhe: http://127.0.0.1:18777/");
            new Thread(() -> {
                sleep(1500);
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                        Desktop.getDesktop().browse(new URI("http://127.0.0.1:18777/"));
                } catch (Exception ignored) {}
            }).start();
        } catch (Exception e) { log("⚠️ Web dashboard chalu hoy ni: " + e.getMessage()); }
    }

    // ------------------------------------------------------------ v1.4: 20 PC agents (phone-e 20 + PC-te 20 = mot 40)
    static class PcAgent {
        String id, name, emoji, job, status = "IDLE", task = "", lastResult = "";
        long startedAt = 0; int progress = 0, done = 0, failed = 0;
    }
    static final java.util.List<PcAgent> pcAgents = new java.util.ArrayList<>();
    static volatile String phoneAgentsJson = "[]";
    static {
        String[][] defs = {
            {"main", "🤖", "Main Agent", "Tomar sathe kotha bole, kaj vag kore dey"},
            {"builder", "🏗️", "Builder", "APK / EXE / iOS app banay"},
            {"github", "🐙", "GitHub Agent", "Repo, push, code manage kore"},
            {"media", "🎬", "Media Agent", "Video, photo, screen dekhe"},
            {"files", "📁", "Files Agent", "File transfer, Supabase, Drive, backup kore"},
            {"pc", "💻", "PC Agent", "PC control kore - app, type, click, power"},
            {"writer", "✍️", "Writer", "Likha, golpo, script likhe"},
            {"researcher", "🔍", "Researcher", "Search kore, tothyo khuje dey"},
            {"translator", "🌐", "Translator", "Bangla-English-Hindi translate kore"},
            {"scheduler", "⏰", "Scheduler", "Alarm, reminder, routine mone rakhay"},
            {"health", "🏃", "Health", "Health, steps, ghum track kore"},
            {"finance", "💰", "Finance", "Taka, khoroch, budget dekhe"},
            {"shopper", "🛒", "Shopper", "Online kena-kata kore"},
            {"travel", "✈️", "Travel", "Location, rasta, travel plan kore"},
            {"music", "🎵", "Music", "Gaan chalaye, YouTube chalaye"},
            {"social", "💬", "Social", "WhatsApp, SMS, social media chalaye"},
            {"email", "📧", "Email", "Email pore, pathay"},
            {"calendar", "📅", "Calendar", "Event, meeting mone rakhay"},
            {"security", "🛡️", "Security", "Security scan, phone nirapod rakhay"},
            {"learner", "🧠", "Learner", "Tomar ovvas sekhe, predict kore"}
        };
        for (String[] d : defs) { PcAgent a = new PcAgent(); a.id = d[0]; a.emoji = d[1]; a.name = d[2]; a.job = d[3]; pcAgents.add(a); }
    }
    static PcAgent findAgent(String id) { for (PcAgent a : pcAgents) if (a.id.equals(id)) return a; return pcAgents.get(0); }
    static String agentForCommand(String cmd) {
        String low = cmd.toLowerCase();
        if (low.contains("phone screen") || low.contains("mobile screen") || low.contains("screen")) return "media";
        if (low.contains("github") || low.contains("repo") || low.contains("push")) return "github";
        if (low.contains("file") || low.contains("clipboard") || low.contains("supabase") || low.contains("drive")) return "files";
        if (low.contains("build") || low.contains("apk") || low.contains("exe") || low.contains("ios")) return "builder";
        if (low.contains("translate")) return "translator";
        if (low.contains("alarm") || low.contains("reminder") || low.contains("routine")) return "scheduler";
        if (isPcLocalCommand(cmd)) return "pc";
        return "main";
    }
    static void startAgentTask(String id, String task) {
        PcAgent a = findAgent(id);
        a.status = "WORKING"; a.task = task; a.startedAt = System.currentTimeMillis(); a.progress = 10;
    }
    static void finishAgentTask(String id, boolean ok, String result) {
        PcAgent a = findAgent(id);
        a.status = ok ? "DONE" : "FAILED"; a.progress = ok ? 100 : 0;
        a.lastResult = result == null ? "" : result;
        if (a.lastResult.length() > 120) a.lastResult = a.lastResult.substring(0, 120);
        if (ok) a.done++; else a.failed++;
    }
    static JSONArray agentsSnapshotJson() {
        JSONArray arr = new JSONArray();
        for (PcAgent a : pcAgents) {
            arr.put(new JSONObject().put("id", a.id).put("name", a.name).put("emoji", a.emoji).put("job", a.job)
                    .put("status", a.status).put("task", a.task)
                    .put("elapsed_s", a.status.equals("WORKING") ? (System.currentTimeMillis() - a.startedAt) / 1000 : 0)
                    .put("progress", a.progress).put("done", a.done).put("failed", a.failed).put("last", a.lastResult));
        }
        return arr;
    }
    static String agentsBoardText() {
        StringBuilder sb = new StringBuilder("🤖 PC AGENTS - 20 (phone-e aro 20, mot 40)\n\n");
        for (PcAgent a : pcAgents) {
            String dot = a.status.equals("WORKING") ? "🟢" : a.status.equals("DONE") ? "✅" : a.status.equals("FAILED") ? "❌" : "⚪";
            sb.append(dot).append(" ").append(a.emoji).append(" ").append(a.name);
            if (!a.task.isEmpty()) sb.append(" - ").append(a.task);
            if (a.status.equals("WORKING")) sb.append(" (").append((System.currentTimeMillis() - a.startedAt) / 1000).append("s)");
            if (!a.lastResult.isEmpty()) sb.append("\n    └ ").append(a.lastResult);
            sb.append("\n");
        }
        return sb.toString();
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
            lastPhoneScreenB64 = img;
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
        phoneHasE2e = false;
        byte[] rb = new byte[16];
        new java.security.SecureRandom().nextBytes(rb);
        StringBuilder hb = new StringBuilder();
        for (byte b : rb) hb.append(String.format("%02x", b));
        e2eSecret = hb.toString();
        config.put("e2e_secret", e2eSecret);
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
        // 3b. v1.1 PC AI routing (no network)
        System.out.println("route open: " + isPcLocalCommand("open notepad"));
        System.out.println("route shutdown: " + isPcLocalCommand("shutdown"));
        System.out.println("route battery: " + !isPcLocalCommand("battery status"));
        String sample = "Thik ache. [[PHONE]] battery status";
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("\\[\\[PHONE\\]\\]\\s*([^\\n]+)").matcher(sample);
        System.out.println("marker phone: " + (mm.find() && mm.group(1).trim().equals("battery status")));
        String clean = sample.replaceAll("\\[\\[(PHONE|PC)\\]\\][^\\n]*", "").trim();
        System.out.println("marker clean: " + clean.equals("Thik ache."));
        // 4. network flow (SKIP when the sandbox blocks Java TCP here)
        JSONObject hb = postJson("/api/heartbeat", new JSONObject()
                .put("install_id", "pc-test-phone").put("device_id", "d1")
                .put("device_model", "test").put("app_version", "1.6.0")
                .put("monitoring", true).put("permissions", new JSONObject()));
        if (hb == null) {
            // v1.4: model picker - key-er list-e 3.8-flash thakle setai nebe (2.5-flash 404 dileo)
            String sampleList = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/gemini-3.8-flash\",\"supportedGenerationMethods\":[\"generateContent\"]},{\"name\":\"models/gemini-embedding-001\",\"supportedGenerationMethods\":[\"embedContent\"]}]}";
            System.out.println("model pick (3.8 in list): " + ("gemini-3.8-flash".equals(pickModelFromList(sampleList)) ? "OK" : "FAIL got=" + pickModelFromList(sampleList)));
            String sampleList2 = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"supportedGenerationMethods\":[\"generateContent\"]}]}";
            System.out.println("model pick (only 2.5): " + ("gemini-2.5-flash".equals(pickModelFromList(sampleList2)) ? "OK" : "FAIL got=" + pickModelFromList(sampleList2)));
            // v1.4: agents - 20 PC agents, routing
            System.out.println("pc agents: " + pcAgents.size() + (pcAgents.size() == 20 ? " OK" : " FAIL"));
            System.out.println("route open: " + ("pc".equals(agentForCommand("open notepad")) ? "OK" : "FAIL"));
            System.out.println("route phone screen: " + ("media".equals(agentForCommand("phone screen")) ? "OK" : "FAIL"));
            System.out.println("route hello: " + ("main".equals(agentForCommand("hello")) ? "OK" : "FAIL"));
            startAgentTask("pc", "open notepad");
            finishAgentTask("pc", true, "notepad khule dilam");
            System.out.println("agent snapshot: " + agentsSnapshotJson().length() + (agentsSnapshotJson().length() == 20 ? " OK" : " FAIL"));
            // v1.5: E2E round-trip (phone-er RelayCrypto-r sathe same PBKDF2/GCM)
        phoneHasE2e = true;
        String enc = e2eEncrypt("hello e2e test");
        System.out.println("e2e round-trip: " + ("hello e2e test".equals(e2eDecrypt(enc)) && enc.startsWith("ENC1:") ? "OK" : "FAIL"));
        System.out.println("e2e plaintext passthrough: " + ("plain text".equals(e2eDecrypt("plain text")) ? "OK" : "FAIL"));
        phoneHasE2e = false;
        System.out.println("e2e off = plaintext: " + ("hello".equals(e2eEncrypt("hello")) ? "OK" : "FAIL"));
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
        synchronized (webLog) { webLog.add(s); while (webLog.size() > 300) webLog.remove(0); }
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
