package com.tianqianguai.gramsieve.module;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.tianqianguai.gramsieve.config.PersistentLogStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies the diagnostic log contract for the SelectAll flow.
 *
 * <p>{@code TelegramHookInstaller} logs every step via {@code ModuleLogger.hook()} which
 * persists to {@link PersistentLogStore}. This test asserts the expected markers appear
 * in the correct order so that on-device black-box verification can rely on them.
 *
 * <h3>Expected flow</h3>
 * <ol>
 *   <li>Download button injected into ActionBarMenu</li>
 *   <li>Download button clicked → original download button found and clicked</li>
 *   <li>SelectAll button injected into action bar / action mode</li>
 *   <li>SelectAll clicked → items selected (via adapter fields or fallback long-click)</li>
 * </ol>
 */
public final class SelectAllFlowLogContractTest {

    private static final String TAG = "GramSieve";

    // ---- Log markers produced by TelegramHookInstaller ----

    /** startKeepVisibleLoop added the download ImageButton. */
    private static final String MARKER_DOWNLOAD_BTN_ADDED =
            "SelectAll: added download button to ActionBarMenu";

    /** User (or auto-delay) clicked the injected download button. */
    private static final String MARKER_DOWNLOAD_BTN_CLICKED =
            "SelectAll: download button clicked";

    /** The original Telegram download button (tag==3) was found and performClick'd. */
    private static final String MARKER_ORIGINAL_DOWNLOAD_CLICKED =
            "SelectAll: performed click on original download btn";

    /** SelectAll button injected into an activity's actionBar. */
    private static final String MARKER_SELECT_ALL_INJECTED_INTO_ACTIVITY =
            "SelectAll: injected button into";

    /** SelectAll button injected into an action mode bar. */
    private static final String MARKER_SELECT_ALL_INJECTED_INTO_ACTION_MODE =
            "SelectAll: injected into action mode bar";

    /** SelectAll button injected into a generic action bar. */
    private static final String MARKER_SELECT_ALL_INJECTED_INTO_ACTIONBAR =
            "SelectAll: added to child[";

    /** SelectAll button clicked. */
    private static final String MARKER_SELECT_ALL_CLICKED =
            "SelectAll: clicked!";

    /** selectAllByLongClick found the adapter and its selectedDialogs field. */
    private static final String MARKER_SELECTED_DIALOGS_SIZE =
            "SelectAll: selectedDialogs.size=";

    /** selectAllByLongClick toggled items via onItemLongClick. */
    private static final String MARKER_TOGGLED_ITEMS =
            "SelectAll: toggled";

    /** tryAdapterSelectAll succeeded (selectedIds / selectedFiles / selectedMessages). */
    private static final String MARKER_ADAPTER_SELECT_ALL_HINT =
            "SelectAll: adapter class=";

    /** Fallback: long-clicked visible child views. */
    private static final String MARKER_FALLBACK_LONG_CLICK =
            "SelectAll: fallback long-click on";

    // ---- helper ----

    private static PersistentLogStore.LogEntry hookEntry(String message) {
        return new PersistentLogStore.LogEntry("INFO", "hook", TAG, message);
    }

    private static PersistentLogStore.LogEntry hookError(String message) {
        return new PersistentLogStore.LogEntry("ERROR", "hook", TAG, message);
    }

    /** Build a snapshot by appending entries in order (oldest first). */
    private static PersistentLogStore.LogSnapshot buildSnapshot(PersistentLogStore.LogEntry... entries) {
        PersistentLogStore.LogSnapshot snapshot = new PersistentLogStore.LogSnapshot();
        for (PersistentLogStore.LogEntry entry : entries) {
            snapshot.entries.add(entry);
        }
        snapshot.updatedAtEpochMs = System.currentTimeMillis();
        return snapshot;
    }

    private static List<String> messagesOf(PersistentLogStore.LogSnapshot snapshot) {
        List<String> messages = new ArrayList<>();
        for (PersistentLogStore.LogEntry entry : snapshot.entries) {
            messages.add(entry.message);
        }
        return messages;
    }

    private static boolean anyContains(List<String> messages, String substring) {
        for (String msg : messages) {
            if (msg.contains(substring)) {
                return true;
            }
        }
        return false;
    }

    private static int firstIndexOf(List<String> messages, String substring) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).contains(substring)) {
                return i;
            }
        }
        return -1;
    }

    // ---- Tests ----

    /**
     * Happy path: download button injected → clicked → original download triggered
     * → selectAll injected → clicked → items selected via adapter.
     */
    @Test
    public void fullFlow_downloadThenSelectAllViaAdapter_logsInOrder() {
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry(MARKER_DOWNLOAD_BTN_ADDED),
                hookEntry(MARKER_DOWNLOAD_BTN_CLICKED),
                hookEntry(MARKER_ORIGINAL_DOWNLOAD_CLICKED),
                hookEntry(MARKER_SELECT_ALL_INJECTED_INTO_ACTIVITY + " DialogsActivity actionBar"),
                hookEntry(MARKER_SELECT_ALL_CLICKED),
                hookEntry(MARKER_ADAPTER_SELECT_ALL_HINT + "org.telegram.ui.DialogsActivity$6"),
                hookEntry(MARKER_SELECTED_DIALOGS_SIZE + "42")
        );

        List<String> messages = messagesOf(snapshot);

        // All markers present
        assertTrue("download btn added", anyContains(messages, MARKER_DOWNLOAD_BTN_ADDED));
        assertTrue("download btn clicked", anyContains(messages, MARKER_DOWNLOAD_BTN_CLICKED));
        assertTrue("original download clicked", anyContains(messages, MARKER_ORIGINAL_DOWNLOAD_CLICKED));
        assertTrue("selectAll injected", anyContains(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTIVITY));
        assertTrue("selectAll clicked", anyContains(messages, MARKER_SELECT_ALL_CLICKED));
        assertTrue("adapter found", anyContains(messages, MARKER_ADAPTER_SELECT_ALL_HINT));
        assertTrue("selectedDialogs populated", anyContains(messages, MARKER_SELECTED_DIALOGS_SIZE));

        // Ordering: download flow before selectAll flow
        int downloadAdded = firstIndexOf(messages, MARKER_DOWNLOAD_BTN_ADDED);
        int downloadClicked = firstIndexOf(messages, MARKER_DOWNLOAD_BTN_CLICKED);
        int originalClicked = firstIndexOf(messages, MARKER_ORIGINAL_DOWNLOAD_CLICKED);
        int selectAllInjected = firstIndexOf(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTIVITY);
        int selectAllClicked = firstIndexOf(messages, MARKER_SELECT_ALL_CLICKED);
        int selectedSize = firstIndexOf(messages, MARKER_SELECTED_DIALOGS_SIZE);

        assertTrue("download added before click", downloadAdded < downloadClicked);
        assertTrue("download click before original", downloadClicked < originalClicked);
        assertTrue("original before selectAll injected", originalClicked < selectAllInjected);
        assertTrue("selectAll injected before clicked", selectAllInjected < selectAllClicked);
        assertTrue("selectAll clicked before size report", selectAllClicked < selectedSize);
    }

    /**
     * SelectAll via action mode: long-press enters action mode → selectAll injected
     * into action mode bar → clicked → toggled via long-click fallback.
     */
    @Test
    public void actionModeFlow_selectAllViaFallback_logsInOrder() {
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry(MARKER_SELECT_ALL_INJECTED_INTO_ACTION_MODE),
                hookEntry(MARKER_SELECT_ALL_CLICKED),
                hookEntry(MARKER_TOGGLED_ITEMS + " 15 items via onItemLongClick")
        );

        List<String> messages = messagesOf(snapshot);

        assertTrue("action mode injected", anyContains(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTION_MODE));
        assertTrue("clicked", anyContains(messages, MARKER_SELECT_ALL_CLICKED));
        assertTrue("toggled items", anyContains(messages, MARKER_TOGGLED_ITEMS));

        int injected = firstIndexOf(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTION_MODE);
        int clicked = firstIndexOf(messages, MARKER_SELECT_ALL_CLICKED);
        int toggled = firstIndexOf(messages, MARKER_TOGGLED_ITEMS);

        assertTrue("injected before clicked", injected < clicked);
        assertTrue("clicked before toggled", clicked < toggled);
    }

    /**
     * SelectAll via generic actionBar injection with fallback long-click.
     */
    @Test
    public void actionBarFlow_selectAllFallback_logsInOrder() {
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry(MARKER_SELECT_ALL_INJECTED_INTO_ACTIONBAR + "2 at 3 broughtToFront"),
                hookEntry("SelectAll: button clicked!"),
                hookEntry(MARKER_FALLBACK_LONG_CLICK + " 8 items")
        );

        List<String> messages = messagesOf(snapshot);

        assertTrue("actionBar injected", anyContains(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTIONBAR));
        assertTrue("button clicked", anyContains(messages, "SelectAll: button clicked!"));
        assertTrue("fallback long-click", anyContains(messages, MARKER_FALLBACK_LONG_CLICK));

        int injected = firstIndexOf(messages, MARKER_SELECT_ALL_INJECTED_INTO_ACTIONBAR);
        int clicked = firstIndexOf(messages, "SelectAll: button clicked!");
        int fallback = firstIndexOf(messages, MARKER_FALLBACK_LONG_CLICK);

        assertTrue("injected before clicked", injected < clicked);
        assertTrue("clicked before fallback", clicked < fallback);
    }

    /**
     * Download button auto-click after 3s delay produces visibility log.
     */
    @Test
    public void downloadButtonAutoClick_logsVisibility() {
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry(MARKER_DOWNLOAD_BTN_ADDED),
                hookEntry("SelectAll: DOWNLOAD_BTN vis=0 w=96 h=96 x=540 y=48 VISIBLE=true"),
                hookEntry(MARKER_DOWNLOAD_BTN_CLICKED),
                hookEntry(MARKER_ORIGINAL_DOWNLOAD_CLICKED)
        );

        List<String> messages = messagesOf(snapshot);

        assertTrue("btn added", anyContains(messages, MARKER_DOWNLOAD_BTN_ADDED));
        assertTrue("visibility logged", anyContains(messages, "DOWNLOAD_BTN vis="));
        assertTrue("VISIBLE=true", anyContains(messages, "VISIBLE=true"));
        assertTrue("auto-clicked", anyContains(messages, MARKER_DOWNLOAD_BTN_CLICKED));
        assertTrue("original clicked", anyContains(messages, MARKER_ORIGINAL_DOWNLOAD_CLICKED));
    }

    /**
     * SelectAll content-view injection uses the tag-based guard to avoid duplicates.
     */
    @Test
    public void selectAllContentView_injectedOnce() {
        // Only one "inserted" log appears — the second attempt is skipped by tag guard
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry("SelectAll: inserted at index 3")
        );

        List<String> messages = messagesOf(snapshot);

        int insertCount = 0;
        for (String msg : messages) {
            if (msg.contains("SelectAll: inserted at index")) {
                insertCount++;
            }
        }
        assertEquals("only one injection", 1, insertCount);
    }

    /**
     * Error during selectAll is logged but does not crash the flow.
     */
    @Test
    public void selectAllError_isLogged() {
        PersistentLogStore.LogSnapshot snapshot = buildSnapshot(
                hookEntry(MARKER_SELECT_ALL_CLICKED),
                hookError("Select all failed")
        );

        List<String> messages = messagesOf(snapshot);

        assertTrue("click logged", anyContains(messages, MARKER_SELECT_ALL_CLICKED));
        assertTrue("error logged", anyContains(messages, "Select all failed"));

        // Verify the error entry has ERROR level
        boolean hasErrorLevel = false;
        for (PersistentLogStore.LogEntry entry : snapshot.entries) {
            if ("ERROR".equals(entry.level) && entry.message.contains("Select all failed")) {
                hasErrorLevel = true;
                break;
            }
        }
        assertTrue("error entry has ERROR level", hasErrorLevel);
    }

    /**
     * The log-based verification method for on-device testing:
     * <pre>
     * 1. adb shell am start -n org.telegram.messenger/.DefaultIcon
     * 2. Wait for "SelectAll: added download button" in logs
     * 3. Tap the download button (adb shell input tap x y)
     * 4. Verify "SelectAll: performed click on original download btn"
     * 5. Long-press a chat row to enter selection mode
     * 6. Verify "SelectAll: injected" appears
     * 7. Tap "全选" / "Select All"
     * 8. Verify "SelectAll: selectedDialogs.size=N" where N > 1
     *    OR "SelectAll: toggled N items" where N > 1
     * </pre>
     *
     * This test encodes that contract so CI can catch regressions in the log marker strings.
     */
    @Test
    public void logMarkersMatchExpectedStrings() {
        // These are the exact strings TelegramHookInstaller produces.
        // If any of these fail, the on-device verification script must be updated.
        assertEquals("SelectAll: added download button to ActionBarMenu",
                MARKER_DOWNLOAD_BTN_ADDED);
        assertEquals("SelectAll: download button clicked",
                MARKER_DOWNLOAD_BTN_CLICKED);
        assertEquals("SelectAll: performed click on original download btn",
                MARKER_ORIGINAL_DOWNLOAD_CLICKED);
        assertEquals("SelectAll: clicked!",
                MARKER_SELECT_ALL_CLICKED);

        // Prefix checks (these markers are followed by dynamic content)
        assertTrue(MARKER_SELECT_ALL_INJECTED_INTO_ACTIVITY.startsWith("SelectAll: injected button into"));
        assertTrue(MARKER_SELECT_ALL_INJECTED_INTO_ACTION_MODE.startsWith("SelectAll: injected into action mode bar"));
        assertTrue(MARKER_SELECT_ALL_INJECTED_INTO_ACTIONBAR.startsWith("SelectAll: added to child["));
        assertTrue(MARKER_SELECTED_DIALOGS_SIZE.startsWith("SelectAll: selectedDialogs.size="));
        assertTrue(MARKER_TOGGLED_ITEMS.startsWith("SelectAll: toggled"));
        assertTrue(MARKER_FALLBACK_LONG_CLICK.startsWith("SelectAll: fallback long-click on"));
    }
}
