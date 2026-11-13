package com.deeptactics.roadie

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context

// 실제 차량 속도. 거리는 속도를 적분해서 낸다. CAR_SPEED 권한을 받은 뒤 만들 것
class CarDrive(context: Context) : DriveSource {
    @Volatile
    override var speedKmh = 0f
        private set
    override var km = 0.0
        private set

    private val car = Car.createCar(context)
    private val props = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            speedKmh = (value.value as Float) * 3.6f
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {}
    }

    init {
        props.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
    }

    override fun update(dt: Double) {
        km += speedKmh / 3600.0 * dt
    }

    override fun stop() {
        props.unregisterCallback(callback)
        car.disconnect()
    }
}
