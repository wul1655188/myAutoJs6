/**
 * BLE HID SDK — Full Interface Test Script
 *
 * Usage in AutoJs6: launch this script, it will handle service binding,
 * connection, execute every BLE command in sequence, and report results.
 */

"use strict";

// Adjust this to your ESP32 device's MAC address
var BLE_DEVICE_ADDRESS = "";

// Screen coordinates for click tests (screen pixels, SDK auto-maps to HID)
var CLICK_X = 540;
var CLICK_Y = 1200;

// Swipe coordinates
var SWIPE_X1 = 200, SWIPE_Y1 = 1200;
var SWIPE_X2 = 900, SWIPE_Y2 = 1200;
var SWIPE_DUR = 800;

// Move deltas
var MOVE_DX = 20, MOVE_DY = 0;

// ---- Internal state ----
var BleHidService, IBleHidService, IBleHidCallback, Intent, Context, ComponentName;
var bleService = null;
var isBound = false;
var isConnected = false;
var testResults = [];
var testsPassed = 0;
var testsFailed = 0;
var currentAction = "";

function log(msg) {
    var ts = new Date().toLocaleTimeString();
    console.log("[" + ts + "] " + msg);
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
        ComponentName = android.content.ComponentName;
        log("Java classes loaded OK");
        return true;
    } catch (e) {
        log("ERROR loading classes: " + e);
        return false;
    }
}

// ---- Service connection callback (Java implementation) ----
var serviceConnection = null;
var bleCallback = null;

function createCallbacks() {
    // BLE callback
    bleCallback = new IBleHidCallback.Stub({
        onConnectionStateChanged: function(state, macAddress) {
            var stateNames = ["disconnected", "connecting", "connected", "ready", "failed"];
            var sname = stateNames[state] || "unknown(" + state + ")";
            log("BLE state: " + sname + "  mac=" + (macAddress || "-"));
            isConnected = (state === 3); // STATE_READY
        },
        onError: function(macAddress, message) {
            log("BLE error: mac=" + (macAddress || "-") + " msg=" + (message || "-"));
        }
    });

    // Service connection
    var self = this;
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
    try {
        bleService.unregisterCallback(bleCallback);
    } catch (e) {}
    try {
        context.unbindService(serviceConnection);
    } catch (e) {}
    bleService = null;
    isBound = false;
    isConnected = false;
    log("Unbound");
}

// ---- Connect / Disconnect ----
function connect() {
    if (!bleService) {
        log("❌ Not bound — cannot connect");
        recordResult("connect", false, "service not bound");
        return false;
    }
    if (!BLE_DEVICE_ADDRESS) {
        log("❌ BLE_DEVICE_ADDRESS is empty — set it before running");
        recordResult("connect", false, "no address");
        return false;
    }
    try {
        bleService.connectToDevice(BLE_DEVICE_ADDRESS);
        log("connectToDevice(" + BLE_DEVICE_ADDRESS + ") — waiting 3s...");
        sleep(3000);
        isConnected = bleService.isConnected();
        recordResult("connect", isConnected, BLE_DEVICE_ADDRESS);
        return isConnected;
    } catch (e) {
        log("connect failed: " + e);
        recordResult("connect", false, e.toString());
        return false;
    }
}

function disconnect() {
    if (!bleService) return;
    try {
        bleService.disconnect();
        sleep(1000);
        isConnected = false;
        recordResult("disconnect", true);
    } catch (e) {
        recordResult("disconnect", false, e.toString());
    }
}

// ---- Test each command ----
function testCommand(actionName, actionFn) {
    if (!isConnected) {
        recordResult(actionName, false, "not connected");
        return;
    }
    try {
        actionFn();
        sleep(500);
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
    testCommand("leftClick", function() { bleService.leftClick(CLICK_X, CLICK_Y); });
    testCommand("move",      function() { bleService.move(MOVE_DX, MOVE_DY); });
    testCommand("move(-dx)", function() { bleService.move(-MOVE_DX, 0); });

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

    log("\n========== Results: " + testsPassed + " passed, " + testsFailed + " failed ==========");
    for (var i = 0; i < testResults.length; i++) {
        log(testResults[i]);
    }
}

// ---- Main ----
(function() {
    log("BLE HID Test Script starting...");

    if (!loadClasses()) {
        log("Cannot load BLE SDK classes — is the SDK integrated?");
        return;
    }

    createCallbacks();

    if (!bindService()) {
        log("Cannot bind BLE service");
        return;
    }
    sleep(500);

    if (!BLE_DEVICE_ADDRESS) {
        log("Set BLE_DEVICE_ADDRESS at the top of this script to your ESP32 MAC");
        // Don't fail — user can set it and run again
        return;
    }

    if (connect()) {
        sleep(500);
        runAllTests();
        sleep(500);
        disconnect();
    }

    sleep(300);
    unbindService();
    log("Test complete.");
})();
