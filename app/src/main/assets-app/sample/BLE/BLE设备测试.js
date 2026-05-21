/**
 * BLE HID SDK — Full Interface Test Script (with Floaty)
 *
 * Usage in AutoJs6: first connect to a BLE device via the drawer,
 * then launch this script. It will detect the connection and run all tests.
 */

"use strict";

// Screen coordinates for click tests (screen pixels)
var CLICK_X = 540;
var CLICK_Y = 1200;

// Swipe coordinates
var SWIPE_X1 = 200, SWIPE_Y1 = 1200;
var SWIPE_X2 = 900, SWIPE_Y2 = 1200;
var SWIPE_DUR = 800;

// Move deltas
var MOVE_DX = 20, MOVE_DY = 0;

// Delay between each API call (ms)
var CALL_DELAY_MS = 5000;

// ---- Internal state ----
var BleHidService, IBleHidService, IBleHidCallback, Intent, Context;
var bleService = null;
var isBound = false;
var isConnected = false;
var testResults = [];
var testsPassed = 0;
var testsFailed = 0;
var currentAction = "";
var floatyWindow = null;

// ---- Floaty Window ----
function createFloatyWindow() {
    floatyWindow = floaty.window(
        <frame id="root" w="320" h="400" gravity="right|top">
            <card cardCornerRadius="8dp" cardElevation="4dp"
                  w="*" h="*" cardBackgroundColor="#F5F5F5">
                <vertical padding="12dp" w="*" h="*">
                    <text id="title" text="BLE HID Test"
                          textSize="16sp" textColor="#333333"
                          textStyle="bold" gravity="center"
                          marginBottom="8dp"/>
                    <frame w="*" h="1dp" backgroundColor="#E0E0E0" marginBottom="8dp"/>
                    <text id="status" text="Initializing..."
                          textSize="12sp" textColor="#666666"
                          gravity="center" marginBottom="8dp"/>
                    <scroll w="*" h="0" layout_weight="1">
                        <text id="log" text="" textSize="11sp"
                              textColor="#555555" lineSpacingMultiplier="1.3"/>
                    </scroll>
                    <frame w="*" h="1dp" backgroundColor="#E0E0E0" marginTop="8dp" marginBottom="8dp"/>
                    <text id="summary" text="Ready"
                          textSize="10sp" textColor="#999999"
                          gravity="center"/>
                </vertical>
            </card>
        </frame>
    );
    floatyWindow.setPosition(20, 200);
    return floatyWindow;
}

function updateFloaty(statusText, logLines, summaryText) {
    if (!floatyWindow) return;
    try {
        floatyWindow.status.setText(statusText || "");
        floatyWindow.log.setText((logLines || []).join("\n"));
        if (summaryText) floatyWindow.summary.setText(summaryText);
    } catch (e) {}
}

var floatyLogLines = [];
function appendFloatyLog(msg) {
    floatyLogLines.push(msg);
    if (floatyLogLines.length > 30) floatyLogLines.shift();
    updateFloaty(currentAction, floatyLogLines,
        "Passed: " + testsPassed + "  Failed: " + testsFailed);
}

// ---- Logging ----
function log(msg) {
    var ts = new Date().toLocaleTimeString();
    var line = "[" + ts + "] " + msg;
    console.log(line);
    appendFloatyLog(line);
}

function recordResult(action, ok, detail) {
    var status = ok ? "PASS" : "FAIL";
    if (ok) testsPassed++; else testsFailed++;
    var entry = status + " | " + action + (detail ? " | " + detail : "");
    testResults.push(entry);
    log(entry);
}

// ---- Load Java classes ----
function loadClasses() {
    try {
        BleHidService = Packages.com.smartfinger.blehidhost.service.BleHidService;
        IBleHidService = Packages.com.smartfinger.blehidhost.ipc.IBleHidService;
        IBleHidCallback = Packages.com.smartfinger.blehidhost.ipc.IBleHidCallback;
        Intent = android.content.Intent;
        Context = android.content.Context;
        log("Java classes loaded OK");
        return true;
    } catch (e) {
        log("ERROR loading classes: " + e);
        return false;
    }
}

// ---- Service connection callback ----
var serviceConnection = null;
var bleCallback = null;

function createCallbacks() {
    bleCallback = new IBleHidCallback.Stub({
        onConnectionStateChanged: function(state, macAddress) {
            var stateNames = ["Disconnected", "Connecting", "Connected", "Ready", "Failed"];
            var sname = stateNames[state] || "Unknown(" + state + ")";
            log("BLE: " + sname + "  mac=" + (macAddress || "-"));
            isConnected = (state >= 2);
        },
        onError: function(macAddress, message) {
            log("BLE error: " + (message || "-"));
        }
    });

    serviceConnection = new android.content.ServiceConnection({
        onServiceConnected: function(name, service) {
            bleService = IBleHidService.Stub.asInterface(service);
            isBound = true;
            try {
                bleService.registerCallback(bleCallback);
                log("Service connected + callback registered");
            } catch (e) {
                log("Callback register failed: " + e);
            }
        },
        onServiceDisconnected: function(name) {
            bleService = null;
            isBound = false;
            isConnected = false;
            log("Service disconnected");
        }
    });
}

// ---- Bind / Unbind ----
function bindService() {
    if (isBound) {
        log("Already bound");
        return true;
    }
    try {
        var intent = new Intent(context, BleHidService.class);
        var ok = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        log("bindService() returned: " + ok);
        return ok;
    } catch (e) {
        log("bindService failed: " + e);
        return false;
    }
}

function unbindService() {
    if (!isBound) return;
    try { bleService.unregisterCallback(bleCallback); } catch (e) {}
    try { context.unbindService(serviceConnection); } catch (e) {}
    bleService = null;
    isBound = false;
    isConnected = false;
    log("Unbound");
}

// ---- Check connection (no connect/disconnect — drawer manages that) ----
function checkConnection() {
    if (!bleService) return false;
    try {
        return bleService.isConnected();
    } catch (e) {
        return false;
    }
}

// ---- Test each command ----
function testCommand(actionName, actionFn) {
    if (!isConnected) {
        recordResult(actionName, false, "not connected");
        return;
    }
    currentAction = actionName;
    updateFloaty(currentAction, floatyLogLines);
    log("--- " + actionName + " ---");
    try {
        actionFn();
        sleep(CALL_DELAY_MS);
        recordResult(actionName, true);
    } catch (e) {
        recordResult(actionName, false, e.toString());
    }
}

function runAllTests() {
    log("\n========== BLE HID Full Test Suite ==========\n");

    // System keys
    testCommand("home",      function() { bleService.home(); });
    testCommand("back",      function() { bleService.back(); });
    testCommand("recents",   function() { bleService.recents(); });

    // Pointer
    testCommand("leftClick(" + CLICK_X + "," + CLICK_Y + ")", function() {
        bleService.leftClick(CLICK_X, CLICK_Y);
    });
    testCommand("move(" + MOVE_DX + "," + MOVE_DY + ")", function() {
        bleService.move(MOVE_DX, MOVE_DY);
    });
    testCommand("move(" + (-MOVE_DX) + ",0)", function() {
        bleService.move(-MOVE_DX, 0);
    });

    // Directional swipes
    testCommand("leftSwipe",  function() { bleService.leftSwipe(); });
    testCommand("rightSwipe", function() { bleService.rightSwipe(); });
    testCommand("upSwipe",    function() { bleService.upSwipe(); });
    testCommand("downSwipe",  function() { bleService.downSwipe(); });

    // Special swipes
    testCommand("webSwipe",   function() { bleService.webSwipe(); });
    testCommand("videoSwipe", function() { bleService.videoSwipe(); });

    // Custom swipe
    testCommand("customSwipe", function() {
        bleService.customSwipe(SWIPE_X1, SWIPE_Y1, SWIPE_X2, SWIPE_Y2, SWIPE_DUR);
    });

    log("\n========== Test Complete: " + testsPassed + " passed, " + testsFailed + " failed ==========");
    currentAction = "Done";
    updateFloaty(currentAction, floatyLogLines,
        "Passed: " + testsPassed + "  Failed: " + testsFailed);
    for (var i = 0; i < testResults.length; i++) {
        log(testResults[i]);
    }
}

// ---- Main ----
(function() {
    log("BLE HID Test Script starting...");
    createFloatyWindow();
    sleep(300);

    if (!loadClasses()) {
        log("Cannot load BLE SDK classes — is the SDK integrated?");
        updateFloaty("Error", floatyLogLines, "Failed to load classes");
        return;
    }

    createCallbacks();

    if (!bindService()) {
        log("Cannot bind BLE service");
        updateFloaty("Error", floatyLogLines, "Failed to bind service");
        return;
    }

    // Wait for service binding to complete
    var waitCount = 0;
    while (!bleService && waitCount < 20) {
        sleep(250);
        waitCount++;
    }

    if (!bleService) {
        log("Service not bound — connect a BLE device in the drawer first");
        updateFloaty("Not connected", floatyLogLines,
            "Please connect a BLE device in the drawer first");
        toast("请先在侧边栏连接蓝牙设备");
        return;
    }

    // Check if already connected (via drawer)
    isConnected = checkConnection();
    if (!isConnected) {
        log("BLE not connected — connect a BLE device in the drawer first");
        updateFloaty("Not connected", floatyLogLines,
            "Please connect a BLE device in the drawer first.");
        toast("请先在侧边栏连接蓝牙设备");
        unbindService();
        return;
    }

    log("BLE connected, running tests...");
    updateFloaty("Running tests...", floatyLogLines);

    runAllTests();

    log("Complete. Keep floaty window open for 10s to review results.");
    sleep(10000);
    floatyWindow.close();
    floatyWindow = null;
    unbindService();
    log("Test done.");
})();
