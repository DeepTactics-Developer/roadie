package com.deeptactics.roadie

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper

// 실제 차량 속도(km/h). CAR_SPEED 권한을 받은 뒤 start()
class CarDrive(context: Context, private val onSpeed: (Float) -> Unit) {
    private val car = Car.createCar(context)
    private val props = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
    private val main = Handler(Looper.getMainLooper())

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val kmh = (value.value as Float) * 3.6f
            main.post { onSpeed(kmh) }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {}
    }

    fun start() {
        props.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    fun stop() {
        props.unregisterCallback(callback)
        car.disconnect()
    }
}
