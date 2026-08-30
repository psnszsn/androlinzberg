package android.webkit

abstract class RenderProcessGoneDetail {
    abstract fun didCrash(): Boolean
    abstract fun rendererPriorityAtExit(): Int
}
