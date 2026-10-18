package io.github.leoalfaroc.jot2press

/** The application the unit tests run in (see robolectric.properties): it builds nothing by itself. */
class TestApp : Jot2PressApp() {
    override fun install() = Unit
}
