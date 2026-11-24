package app.tubehaus.ios

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator

/**
 * The app's haptics on the iPhone's Taptic Engine, as YouTube's iPhone app plays them: a selection
 * tick for tabs and seek steps, a light or medium tap for toggles and gesture thresholds, a heavy one
 * for a long press, a success pattern for confirmations. Compose's own iOS path plays only a couple
 * of these kinds (and the newer ones such as SegmentTick not at all). iPads and the simulator have no
 * motor: the generators then do nothing.
 */
object Haptics {
    private val selection by lazy { UISelectionFeedbackGenerator() }
    private val light by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
    private val medium by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium) }
    private val heavy by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }
    private val note by lazy { UINotificationFeedbackGenerator() }

    /** true when played; false leaves it to Compose's own path */
    fun play(t: HapticFeedbackType): Boolean {
        when (t) {
            HapticFeedbackType.SegmentTick, HapticFeedbackType.SegmentFrequentTick, HapticFeedbackType.TextHandleMove -> selection.selectionChanged()
            HapticFeedbackType.ContextClick, HapticFeedbackType.VirtualKey, HapticFeedbackType.ToggleOff -> light.impactOccurred()
            HapticFeedbackType.ToggleOn, HapticFeedbackType.GestureThresholdActivate, HapticFeedbackType.GestureEnd -> medium.impactOccurred()
            HapticFeedbackType.LongPress -> heavy.impactOccurred()
            HapticFeedbackType.Confirm -> note.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticFeedbackType.Reject -> note.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
            else -> return false
        }
        return true
    }
}
