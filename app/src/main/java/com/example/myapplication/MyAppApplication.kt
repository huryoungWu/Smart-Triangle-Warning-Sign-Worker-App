package com.example.myapplication

import android.app.Application
import com.example.myapplication.di.ServiceLocator

class MyAppApplication : Application() {

    lateinit var serviceLocator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        serviceLocator = ServiceLocator(this)
    }
}
