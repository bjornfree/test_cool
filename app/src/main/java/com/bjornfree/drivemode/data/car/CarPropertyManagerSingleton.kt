package com.bjornfree.drivemode.data.car

import android.content.Context
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton для управления единственным instance CarPropertyManager.
 * @param context Application context для создания Car instance
 */
class CarPropertyManagerSingleton(private val context: Context) {

    companion object {
        private const val TAG = "CarPropertyManager"

        // Имена классов для reflection
        private const val CLASS_CAR = "android.car.Car"
        private const val CLASS_CAR_PROPERTY_MANAGER = "android.car.hardware.property.CarPropertyManager"
        private const val CAR_PROPERTY_SERVICE_NAME = "property"
    }

    // Car API instances
    @Volatile
    private var carInstance: Any? = null

    @Volatile
    private var managerInstance: Any? = null

    @Volatile
    private var isInitialized = false

    // Флаг недоступности Car API (например, на эмуляторе)
    @Volatile
    private var carApiUnavailable = false

    // Флаг для логирования недоступности только один раз
    @Volatile
    private var unavailabilityLogged = false

    // Кэш для reflection methods (ключевая оптимизация)
    private val methodCache = ConcurrentHashMap<String, Method>()

    // Reflection classes cache
    private var carClass: Class<*>? = null
    private var managerClass: Class<*>? = null

    /**
     * Инициализация Car API и CarPropertyManager.
     * Thread-safe, может быть вызвана многократно без вреда.
     *
     * @return true если инициализация успешна или уже выполнена
     */
    @Synchronized
    fun initialize(): Boolean {
        // Если Car API недоступен (например, на эмуляторе), не пытаемся инициализировать
        if (carApiUnavailable) {
            return false
        }

        if (isInitialized && carInstance != null && managerInstance != null) {
            return true
        }

        try {
            Log.i(TAG, "Initializing CarPropertyManager singleton...")

            // 1. Загружаем Car class
            carClass = Class.forName(CLASS_CAR)
            Log.d(TAG, "Car class loaded")

            // 2. Создаем Car instance через Car.createCar(Context)
            val createCarMethod = carClass!!.getMethod("createCar", Context::class.java)
            val car = createCarMethod.invoke(null, context)
            carInstance = car
            Log.d(TAG, "Car instance created")

            // 3. Подключаемся (если метод есть и не подключен)
            try {
                // Проверяем состояние подключения перед вызовом connect()
                val isConnectedMethod = carClass!!.getMethod("isConnected")
                val isConnected = isConnectedMethod.invoke(car) as? Boolean ?: false

                if (!isConnected) {
                    val connectMethod = carClass!!.getMethod("connect")
                    connectMethod.invoke(car)
                    Log.d(TAG, "Car.connect() called")
                } else {
                    Log.d(TAG, "Car already connected")
                }
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "Car.connect() not available (optional)")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "Car already connecting/connected: ${e.message}")
            }

            // 4. Получаем CarPropertyManager через getCarManager("property")
            val getCarManagerMethod = carClass!!.getMethod("getCarManager", String::class.java)
            val manager = getCarManagerMethod.invoke(car, CAR_PROPERTY_SERVICE_NAME)
            managerInstance = manager
            Log.d(TAG, "CarPropertyManager obtained")

            // 5. Загружаем CarPropertyManager class для кэширования методов
            managerClass = Class.forName(CLASS_CAR_PROPERTY_MANAGER)

            // 6. Кэшируем основные методы для быстрого доступа
            cacheCommonMethods()

            isInitialized = true
            Log.i(TAG, "CarPropertyManager singleton initialized successfully")
            return true

        } catch (e: ClassNotFoundException) {
            // Car API недоступен (эмулятор или устройство без Car API)
            carApiUnavailable = true
            if (!unavailabilityLogged) {
                Log.w(TAG, "Car API не доступен (эмулятор или устройство без Car API). Приложение работает в ограниченном режиме.")
                unavailabilityLogged = true
            }
            carInstance = null
            managerInstance = null
            isInitialized = false
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CarPropertyManager", e)
            carInstance = null
            managerInstance = null
            isInitialized = false
            return false
        }
    }

    /**
     * Кэширует часто используемые методы для быстрого доступа.
     * Вызывается один раз при инициализации.
     */
    private fun cacheCommonMethods() {
        try {
            // Кэшируем getIntProperty
            methodCache["getIntProperty"] = managerClass!!.getMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            // Кэшируем getFloatProperty
            methodCache["getFloatProperty"] = managerClass!!.getMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )

            Log.d(TAG, "Common methods cached: ${methodCache.size} methods")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache some methods", e)
        }
    }

    /**
     * Получает закэшированный Method object или ищет его через reflection.
     *
     * @param methodName имя метода
     * @param parameterTypes типы параметров
     * @return Method object или null если не найден
     */
    private fun getCachedMethod(methodName: String, vararg parameterTypes: Class<*>): Method? {
        val cacheKey = methodName
        return methodCache.getOrPut(cacheKey) {
            try {
                managerClass!!.getMethod(methodName, *parameterTypes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get method: $methodName", e)
                throw e
            }
        }
    }

    /**
     * Читает Int property из CarPropertyManager.
     *
     * @param propertyId ID свойства (из VehiclePropertyConstants)
     * @param areaId area ID (по умолчанию 0)
     * @return значение property или null в случае ошибки
     */
    fun readIntProperty(propertyId: Int, areaId: Int = 0): Int? {
        if (!ensureInitialized()) {
            return null
        }

        return try {
            val method = getCachedMethod(
                "getIntProperty",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            val result = method?.invoke(managerInstance, propertyId, areaId)
            result as? Int
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read int property 0x${Integer.toHexString(propertyId)}", e)
            null
        }
    }

    /**
     * Читает Float property из CarPropertyManager.
     *
     * @param propertyId ID свойства (из VehiclePropertyConstants)
     * @param areaId area ID (по умолчанию 0)
     * @return значение property или null в случае ошибки
     */
    fun readFloatProperty(propertyId: Int, areaId: Int = 0): Float? {
        if (!ensureInitialized()) {
            return null
        }

        return try {
            val method = getCachedMethod(
                "getFloatProperty",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            val result = method?.invoke(managerInstance, propertyId, areaId)
            result as? Float
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read float property 0x${Integer.toHexString(propertyId)}", e)
            null
        }
    }

    /**
     * Записывает Int property в CarPropertyManager.
     *
     * @param propertyId ID свойства
     * @param areaId area ID (по умолчанию 0)
     * @param value значение для записи
     * @return true если запись успешна
     */
    fun writeIntProperty(propertyId: Int, value: Int, areaId: Int = 0): Boolean {
        if (!ensureInitialized()) {
            return false
        }

        return try {
            val method = getCachedOrCreateMethod(
                "setIntProperty",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!
            )
            method?.invoke(managerInstance, propertyId, areaId, value)
            Log.d(TAG, "Set int property 0x${Integer.toHexString(propertyId)} = $value")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write int property 0x${Integer.toHexString(propertyId)}", e)
            false
        }
    }

    /**
     * Записывает Float property в CarPropertyManager.
     *
     * @param propertyId ID свойства
     * @param areaId area ID (по умолчанию 0)
     * @param value значение для записи
     * @return true если запись успешна
     */
    fun writeFloatProperty(propertyId: Int, value: Float, areaId: Int = 0): Boolean {
        if (!ensureInitialized()) {
            return false
        }

        return try {
            val method = getCachedOrCreateMethod(
                "setFloatProperty",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
                Float::class.javaPrimitiveType!!
            )
            method?.invoke(managerInstance, propertyId, areaId, value)
            Log.d(TAG, "Set float property 0x${Integer.toHexString(propertyId)} = $value")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write float property 0x${Integer.toHexString(propertyId)}", e)
            false
        }
    }

    /**
     * Получает закэшированный Method или создает новый.
     * Улучшенная версия getCachedMethod с автоматическим кэшированием.
     */
    private fun getCachedOrCreateMethod(methodName: String, vararg parameterTypes: Class<*>): Method? {
        val cacheKey = methodName
        return methodCache.getOrPut(cacheKey) {
            try {
                managerClass!!.getMethod(methodName, *parameterTypes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get method: $methodName", e)
                null
            }
        } as? Method
    }

    /**
     * Регистрирует callback для отслеживания изменений свойства.
     *
     * @param propertyId ID свойства для мониторинга
     * @param areaId area ID (по умолчанию 0)
     * @param callback функция которая будет вызвана при изменении (получает Int значение)
     * @return listener object который нужно сохранить для отписки, или null при ошибке
     */
    fun registerIntPropertyCallback(
        propertyId: Int,
        areaId: Int = 0,
        callback: (Int) -> Unit
    ): Any? {
        if (!ensureInitialized()) {
            return null
        }

        return try {
            // Загружаем CarPropertyEventCallback class
            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")

            // Создаем dynamic proxy для реализации callback
            val callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onChangeEvent" -> {
                        // Вызывается при изменении свойства
                        val event = args?.getOrNull(0)
                        if (event != null) {
                            try {
                                // Извлекаем значение из CarPropertyValue
                                val valueMethod = event.javaClass.getMethod("getValue")
                                val value = valueMethod.invoke(event) as? Int
                                if (value != null) {
                                    callback(value)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error in onChangeEvent callback", e)
                            }
                        }
                        null
                    }
                    "onErrorEvent" -> {
                        // Игнорируем ошибки
                        null
                    }
                    "hashCode" -> {
                        // HashMap требует hashCode для хранения callback
                        System.identityHashCode(proxy)
                    }
                    "equals" -> {
                        // Сравнение объектов по ссылке
                        val other = args?.getOrNull(0)
                        proxy === other
                    }
                    "toString" -> {
                        "CarPropertyCallback@${Integer.toHexString(System.identityHashCode(proxy))}"
                    }
                    else -> null
                }
            }

            // Регистрируем callback через registerCallback
            val registerMethod = managerClass!!.getMethod(
                "registerCallback",
                callbackClass,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            )
            registerMethod.invoke(managerInstance, callbackProxy, propertyId, 0f)

            Log.d(TAG, "Registered callback for property 0x${Integer.toHexString(propertyId)}")
            callbackProxy
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register callback for property 0x${Integer.toHexString(propertyId)}", e)
            null
        }
    }

    /**
     * Отменяет регистрацию callback.
     *
     * @param listener object, возвращенный из registerIntPropertyCallback
     */
    fun unregisterCallback(listener: Any?) {
        if (listener == null || !ensureInitialized()) {
            return
        }

        try {
            val callbackClass = Class.forName("android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback")
            val unregisterMethod = managerClass!!.getMethod("unregisterCallback", callbackClass)
            unregisterMethod.invoke(managerInstance, listener)
            Log.d(TAG, "Unregistered callback")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister callback", e)
        }
    }

    /**
     * Проверяет что singleton инициализирован, и переподключается если нужно.
     *
     * @return true если готов к работе
     */
    private fun ensureInitialized(): Boolean {
        // Если Car API недоступен, сразу возвращаем false без логов
        if (carApiUnavailable) {
            return false
        }

        if (isInitialized && managerInstance != null) {
            return true
        }

        // Логируем попытку инициализации только если Car API потенциально доступен
        Log.w(TAG, "CarPropertyManager not initialized, attempting initialization...")
        return initialize()
    }

    /**
     * Проверяет доступность Car API.
     * Полезно для UI чтобы показать статус или переключиться в demo mode.
     *
     * @return true если Car API доступен и инициализирован
     */
    fun isCarApiAvailable(): Boolean {
        return !carApiUnavailable && isInitialized
    }

    /**
     * Получает raw CarPropertyManager instance для продвинутых случаев
     * (например, для регистрации callbacks в VehicleMetricsService).
     *
     * @return CarPropertyManager instance или null
     */
    fun getManagerInstance(): Any? {
        ensureInitialized()
        return managerInstance
    }

    /**
     * Получает raw Car instance.
     *
     * @return Car instance или null
     */
    fun getCarInstance(): Any? {
        ensureInitialized()
        return carInstance
    }

    /**
     * Освобождает ресурсы и отключается от Car API.
     * Вызывается при остановке приложения.
     */
    @Synchronized
    fun release() {
        try {
            val car = carInstance
            if (car != null && carClass != null) {
                try {
                    val disconnectMethod = carClass!!.getMethod("disconnect")
                    disconnectMethod.invoke(car)
                    Log.i(TAG, "Car.disconnect() called")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disconnect Car", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during release()", e)
        } finally {
            carInstance = null
            managerInstance = null
            isInitialized = false
            methodCache.clear()
            Log.i(TAG, "CarPropertyManager singleton released")
        }
    }

    /**
     * Получает статус инициализации.
     *
     * @return true если singleton готов к работе
     */
    fun isReady(): Boolean = isInitialized && managerInstance != null


}
