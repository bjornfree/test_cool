package com.bjornfree.drivemode

import android.app.Application
import android.util.Log
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.logger.Level
import com.bjornfree.drivemode.di.appModule
import com.bjornfree.drivemode.data.car.CarPropertyManagerSingleton

/**
 * Application класс для инициализации Koin DI.
 *
 * Инициализирует Koin при старте приложения и предоставляет
 * dependency injection для всех компонентов приложения.
 */
class DriveModeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Log.i(TAG, "Initializing DriveMode Application...")

        try {
            startKoin {
                // Логирование Koin (только в debug режиме)
                androidLogger(Level.ERROR)

                // Android контекст для Koin
                androidContext(this@DriveModeApplication)

                // Модули с зависимостями
                modules(appModule)
            }

            Log.i(TAG, "Koin DI initialized successfully")

            // Инициализация ECarX AdaptAPI для управления функциями автомобиля
            initializeECarXAdaptAPI()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Koin DI", e)
        }
    }

    /**
     * Инициализация ECarX AdaptAPI и инжектирование ICarFunction в CarPropertyManagerSingleton.
     * Это необходимо для управления функциями автомобиля (например, режимом вождения).
     */
    private fun initializeECarXAdaptAPI() {
        try {
            Log.i(TAG, "Initializing ECarX AdaptAPI...")

            // Получаем CarPropertyManagerSingleton из Koin
            val carPropertyManager = GlobalContext.get().get<CarPropertyManagerSingleton>()
            Log.d(TAG, "CarPropertyManagerSingleton obtained from Koin")

            // Пытаемся создать экземпляр ECarX AdaptAPI через reflection
            // (чтобы не падать на устройствах без ECarX)
            try {
                val adaptAPIClass = Class.forName("com.ecarx.xui.adaptapi.AdaptAPI")
                Log.d(TAG, "ECarX AdaptAPI class found")

                // Логируем доступные методы для отладки
                logAvailableMethods(adaptAPIClass, "AdaptAPI")

                // Пробуем разные варианты получения instance
                var adaptAPIInstance: Any? = null
                var creationMethod = ""

                // Вариант 1: Singleton get() без параметров
                try {
                    val getInstance = adaptAPIClass.getMethod("get")
                    adaptAPIInstance = getInstance.invoke(null)
                    creationMethod = "get()"
                    Log.d(TAG, "AdaptAPI created via get()")
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "Method get() not found, trying constructor...")
                }

                // Вариант 2: Конструктор с Context
                if (adaptAPIInstance == null) {
                    try {
                        val constructor = adaptAPIClass.getConstructor(android.content.Context::class.java)
                        adaptAPIInstance = constructor.newInstance(this)
                        creationMethod = "constructor(Context)"
                        Log.d(TAG, "AdaptAPI created via constructor(Context)")
                    } catch (e: NoSuchMethodException) {
                        Log.d(TAG, "Constructor(Context) not found, trying empty constructor...")
                    }
                }

                // Вариант 3: Пустой конструктор + init
                if (adaptAPIInstance == null) {
                    try {
                        val constructor = adaptAPIClass.getConstructor()
                        adaptAPIInstance = constructor.newInstance()
                        creationMethod = "constructor()"
                        Log.d(TAG, "AdaptAPI created via empty constructor")

                        // Пытаемся вызвать init(Context)
                        try {
                            val initMethod = adaptAPIClass.getMethod("init", android.content.Context::class.java)
                            initMethod.invoke(adaptAPIInstance, this)
                            Log.d(TAG, "AdaptAPI.init(Context) called")
                        } catch (e: Exception) {
                            Log.d(TAG, "No init(Context) method found")
                        }
                    } catch (e: NoSuchMethodException) {
                        Log.e(TAG, "No suitable constructor found for AdaptAPI")
                    }
                }

                if (adaptAPIInstance != null) {
                    Log.d(TAG, "ECarX AdaptAPI instance created via: $creationMethod")

                    // Получаем ICarFunction из AdaptAPI
                    try {
                        // Логируем доступные методы instance
                        logAvailableMethods(adaptAPIInstance.javaClass, "AdaptAPI instance")

                        val getCarFunction = adaptAPIClass.getMethod("getCarFunction")
                        val carFunction = getCarFunction.invoke(adaptAPIInstance)
                        Log.d(TAG, "ICarFunction obtained from AdaptAPI: ${carFunction != null}")

                        // Инжектируем ICarFunction в CarPropertyManagerSingleton
                        carPropertyManager.setICarFunction(carFunction as? com.ecarx.xui.adaptapi.car.base.ICarFunction)

                        Log.i(TAG, "✓ ECarX AdaptAPI initialized successfully via $creationMethod, ICarFunction injected")
                    } catch (e: NoSuchMethodException) {
                        Log.w(TAG, "Method getCarFunction() not found in AdaptAPI - trying fallback methods")
                        tryDirectICarFunctionCreation(carPropertyManager)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting ICarFunction from AdaptAPI", e)
                        tryDirectICarFunctionCreation(carPropertyManager)
                    }

                    // КРИТИЧНО: Получаем IDriveMode для управления режимами вождения
                    try {
                        Log.d(TAG, "Trying to get IDriveMode...")

                        // Попытка 1: через AdaptAPI -> getVehicle() -> getDriveMode()
                        try {
                            val getVehicle = adaptAPIClass.getMethod("getVehicle")
                            val vehicleInstance = getVehicle.invoke(adaptAPIInstance)
                            Log.d(TAG, "Vehicle obtained from AdaptAPI: ${vehicleInstance != null}")

                            if (vehicleInstance != null) {
                                val vehicleClass = vehicleInstance.javaClass
                                val getDriveMode = vehicleClass.getMethod("getDriveMode")
                                val driveModeInstance = getDriveMode.invoke(vehicleInstance)
                                Log.d(TAG, "IDriveMode obtained from Vehicle: ${driveModeInstance != null}")

                                carPropertyManager.setIDriveMode(driveModeInstance as? com.ecarx.xui.adaptapi.car.vehicle.IDriveMode)
                                Log.i(TAG, "✓ IDriveMode injected via AdaptAPI!")
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed via AdaptAPI.getVehicle(), trying direct Car approach", e)

                            // Попытка 2: через прямой Car.create() -> iDriveMode
                            try {
                                val carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
                                val createMethod = carClass.getMethod("create", android.content.Context::class.java)
                                val carInstance = createMethod.invoke(null, this)
                                Log.d(TAG, "Car instance created directly for IDriveMode")

                                if (carInstance != null) {
                                    // Пробуем получить IDriveMode
                                    try {
                                        val iDriveModeField = carClass.getField("iDriveMode")
                                        val driveModeInstance = iDriveModeField.get(carInstance)
                                        carPropertyManager.setIDriveMode(driveModeInstance as? com.ecarx.xui.adaptapi.car.vehicle.IDriveMode)
                                        Log.i(TAG, "✓ IDriveMode injected via Car.iDriveMode!")
                                    } catch (e2: Exception) {
                                        Log.w(TAG, "No iDriveMode field, trying getDriveMode() method")
                                        val getDriveModeMethod = carClass.getMethod("getDriveMode")
                                        val driveModeInstance = getDriveModeMethod.invoke(carInstance)
                                        carPropertyManager.setIDriveMode(driveModeInstance as? com.ecarx.xui.adaptapi.car.vehicle.IDriveMode)
                                        Log.i(TAG, "✓ IDriveMode injected via Car.getDriveMode()!")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to get IDriveMode via Car", e)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get IDriveMode", e)
                    }
                } else {
                    Log.e(TAG, "Failed to create AdaptAPI instance - trying direct ICarFunction creation")
                    tryDirectICarFunctionCreation(carPropertyManager)
                }
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "ECarX AdaptAPI not available on this device (normal for non-ECarX devices)")
            } catch (e: NoSuchMethodException) {
                Log.e(TAG, "ECarX AdaptAPI method not found (API version mismatch?)", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize ECarX AdaptAPI", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject ICarFunction", e)
        }
    }

    /**
     * Пытается создать ICarFunction напрямую без AdaptAPI.
     * Альтернативный метод на случай, если AdaptAPI недоступен.
     */
    private fun tryDirectICarFunctionCreation(carPropertyManager: CarPropertyManagerSingleton) {
        try {
            Log.i(TAG, "========================================")
            Log.i(TAG, "Trying direct ICarFunction creation...")
            Log.i(TAG, "========================================")

            // Вариант 0A: Прямое использование библиотеки (без reflection)
            Log.d(TAG, "Trying direct com.ecarx.xui.adaptapi.car.Car.create()...")
            try {
                val car = com.ecarx.xui.adaptapi.car.Car.create(this)
                Log.d(TAG, "✓ Car created directly")

                val carFunction = car.iCarFunction
                Log.d(TAG, "✓ ICarFunction obtained directly: ${carFunction != null}")

                if (carFunction != null) {
                    carPropertyManager.setICarFunction(carFunction)
                    Log.i(TAG, "✓✓✓ ICarFunction injected via direct Car.create() (no reflection) ✓✓✓")
                    return
                }
            } catch (e: NoClassDefFoundError) {
                Log.d(TAG, "Direct Car access not available (expected in some builds)")
            } catch (e: Exception) {
                Log.d(TAG, "✗ Direct Car.create() failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Вариант 0B: Car.create(context).iCarFunction через reflection
            Log.d(TAG, "Trying Car.create(context).iCarFunction via reflection...")
            try {
                val carClass = Class.forName("com.ecarx.xui.adaptapi.car.Car")
                Log.d(TAG, "✓ Car class found")

                // Car.create(Context)
                val createMethod = carClass.getMethod("create", android.content.Context::class.java)
                val carInstance = createMethod.invoke(null, this)
                Log.d(TAG, "✓ Car instance created")

                if (carInstance != null) {
                    // Получаем iCarFunction через getter
                    try {
                        val getICarFunctionMethod = carInstance.javaClass.getMethod("getICarFunction")
                        val carFunction = getICarFunctionMethod.invoke(carInstance)
                        Log.d(TAG, "✓ ICarFunction obtained via getICarFunction(): ${carFunction != null}")

                        if (carFunction != null) {
                            carPropertyManager.setICarFunction(carFunction as? com.ecarx.xui.adaptapi.car.base.ICarFunction)
                            Log.i(TAG, "✓✓✓ ICarFunction injected via Car.create(context).getICarFunction() ✓✓✓")
                            return
                        }
                    } catch (e: NoSuchMethodException) {
                        Log.d(TAG, "Method getICarFunction() not found, trying field access...")

                        // Пытаемся получить через поле
                        try {
                            val iCarFunctionField = carInstance.javaClass.getField("iCarFunction")
                            val carFunction = iCarFunctionField.get(carInstance)
                            Log.d(TAG, "✓ ICarFunction obtained via field: ${carFunction != null}")

                            if (carFunction != null) {
                                carPropertyManager.setICarFunction(carFunction as? com.ecarx.xui.adaptapi.car.base.ICarFunction)
                                Log.i(TAG, "✓✓✓ ICarFunction injected via Car.create(context).iCarFunction ✓✓✓")
                                return
                            }
                        } catch (e: NoSuchFieldException) {
                            Log.d(TAG, "Field iCarFunction not found")
                            logAvailableMethods(carInstance.javaClass, "Car instance")
                        }
                    }
                }
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "✗ Car class not found")
            } catch (e: Exception) {
                Log.d(TAG, "✗ Car.create() failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Вариант 1: VehicleFactory
            Log.d(TAG, "Trying VehicleFactory...")
            try {
                val vehicleFactoryClass = Class.forName("com.ecarx.xui.adaptapi.car.vehicle.VehicleFactory")
                Log.d(TAG, "✓ VehicleFactory class found")
                logAvailableMethods(vehicleFactoryClass, "VehicleFactory")

                // Пытаемся получить create метод
                val createMethod = vehicleFactoryClass.getMethod("create", android.content.Context::class.java)
                val vehicleFactory = createMethod.invoke(null, this)
                Log.d(TAG, "✓ VehicleFactory instance created")

                // Получаем IVehicle
                val getVehicleMethod = vehicleFactoryClass.getMethod("getVehicle")
                val vehicle = getVehicleMethod.invoke(vehicleFactory)
                Log.d(TAG, "✓ IVehicle obtained: ${vehicle != null}")

                if (vehicle != null) {
                    // IVehicle наследует ICarFunction
                    carPropertyManager.setICarFunction(vehicle as? com.ecarx.xui.adaptapi.car.base.ICarFunction)
                    Log.i(TAG, "✓✓✓ ICarFunction injected via VehicleFactory ✓✓✓")
                    return
                }
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "✗ VehicleFactory class not found")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "✗ VehicleFactory method not found: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "✗ VehicleFactory failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Вариант 2: CarFactory
            Log.d(TAG, "Trying CarFactory...")
            try {
                val carFactoryClass = Class.forName("com.ecarx.xui.adaptapi.car.CarFactory")
                Log.d(TAG, "✓ CarFactory class found")
                logAvailableMethods(carFactoryClass, "CarFactory")

                // Пытаемся получить create метод
                val createMethod = carFactoryClass.getMethod("create", android.content.Context::class.java)
                val carFactory = createMethod.invoke(null, this)
                Log.d(TAG, "✓ CarFactory instance created")

                // Получаем ICar
                val getCarMethod = carFactoryClass.getMethod("getCar")
                val car = getCarMethod.invoke(carFactory)
                Log.d(TAG, "✓ ICar obtained: ${car != null}")

                if (car != null) {
                    // Пытаемся получить ICarFunction из ICar
                    logAvailableMethods(car.javaClass, "ICar instance")
                    val getCarFunctionMethod = car.javaClass.getMethod("getCarFunction")
                    val carFunction = getCarFunctionMethod.invoke(car)

                    carPropertyManager.setICarFunction(carFunction as? com.ecarx.xui.adaptapi.car.base.ICarFunction)
                    Log.i(TAG, "✓✓✓ ICarFunction injected via CarFactory ✓✓✓")
                    return
                }
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "✗ CarFactory class not found")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "✗ CarFactory method not found: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "✗ CarFactory failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            // Вариант 3: Прямое создание через конструктор ICarFunction реализации
            Log.d(TAG, "Trying direct CarFunctionImpl creation...")
            try {
                val carFunctionClass = Class.forName("com.ecarx.xui.adaptapi.car.base.CarFunctionImpl")
                Log.d(TAG, "✓ CarFunctionImpl class found")

                val constructor = carFunctionClass.getConstructor(android.content.Context::class.java)
                val carFunction = constructor.newInstance(this)

                carPropertyManager.setICarFunction(carFunction as? com.ecarx.xui.adaptapi.car.base.ICarFunction)
                Log.i(TAG, "✓✓✓ ICarFunction injected via direct CarFunctionImpl creation ✓✓✓")
                return
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "✗ CarFunctionImpl class not found")
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "✗ CarFunctionImpl constructor not found: ${e.message}")
            } catch (e: Exception) {
                Log.d(TAG, "✗ Direct CarFunctionImpl creation failed: ${e.javaClass.simpleName}: ${e.message}")
            }

            Log.e(TAG, "========================================")
            Log.e(TAG, "✗✗✗ All ICarFunction creation methods FAILED ✗✗✗")
            Log.e(TAG, "Drive mode control will NOT work!")
            Log.e(TAG, "========================================")
        } catch (e: Exception) {
            Log.e(TAG, "Error in tryDirectICarFunctionCreation", e)
        }
    }

    /**
     * Логирует доступные методы класса для отладки.
     */
    private fun logAvailableMethods(clazz: Class<*>, className: String) {
        try {
            val methods = clazz.methods
            Log.d(TAG, "Available methods in $className:")
            methods.filter { it.name.contains("get") || it.name.contains("create") || it.name.contains("init") }
                .forEach { method ->
                    val params = method.parameterTypes.joinToString(", ") { it.simpleName }
                    Log.d(TAG, "  - ${method.name}($params): ${method.returnType.simpleName}")
                }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to log methods for $className: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DriveModeApplication"
    }
}
