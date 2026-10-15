package io.github.leoalfaroc.journal

/** The application the unit tests run in (see robolectric.properties): it builds nothing by itself. */
class TestApp : JournalApp() {
    override fun install() = Unit
}
