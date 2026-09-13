package com.zz.filemanager.benchmark

import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrimaryJourneysBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    @Test fun coldStartupAndHomeFrameTiming() = benchmark.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
        iterations = 5,
        setupBlock = { pressHome() },
    ) { startActivityAndWait() }
    companion object { private const val PACKAGE = "com.zz.filemanager" }
}

@RunWith(AndroidJUnit4::class)
class PrimaryJourneysBaselineProfile {
    @get:Rule val baseline = BaselineProfileRule()
    @Test fun generate() = baseline.collect(packageName = "com.zz.filemanager") {
        pressHome(); startActivityAndWait(); device.waitForIdle()
    }
}
