package com.ykinger.stalkertv

import android.app.Application
import com.ykinger.stalkertv.core.ContentRepository
import com.ykinger.stalkertv.core.MockContentRepository
import com.ykinger.stalkertv.core.MockScenario

// Android's recommended manual dependency injection via an application container.
class AppContainer(private val connectedRepository: ContentRepository? = null) {
    fun repository(scenario: MockScenario): ContentRepository =
        connectedRepository ?: MockContentRepository(scenario = scenario)
}

class TvApplication : Application() {
    val container = AppContainer()
}
