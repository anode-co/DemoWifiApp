package co.anode.demowifiapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.*
import android.net.ConnectivityManager.NetworkCallback
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.*
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.*
import java.util.*
import java.util.concurrent.Executors


class MainActivity : AppCompatActivity() {
    private val SSID = "Pkt.cube"
    @RequiresApi(33)
    private val REQUIRED_SDK_PERMISSIONS = arrayOf(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.WRITE_SETTINGS,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.NEARBY_WIFI_DEVICES
    )
    private val TAG = "co.anode.DemoWifiApp"
    val mServiceName = "OpenWrt"
    val SERVICE_TYPE = "_udpdummy._udp."
    private lateinit var nsdManager: NsdManager
    var mService: NsdServiceInfo = NsdServiceInfo()
    lateinit var mServiceHost: InetAddress
    var mServicePort = 0
    lateinit var udpSocket: DatagramSocket
    lateinit var status: TextView
    private var nsdListenerActive = false

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            nsdListenerActive = true
            Log.d(TAG, "Service discovery started")
            runOnUiThread {
                status.text = "Service discovery started"
            }
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d(TAG, "Service discovery success$service")
            nsdListenerActive = true
            when {
                ((service.serviceType == SERVICE_TYPE) &&
                        (service.serviceName == mServiceName)) -> nsdManager.resolveService(service, resolveListener)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            nsdListenerActive = true
            Log.e(TAG, "service lost: $service")
            runOnUiThread {
                status.text ="service lost: $service"
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            nsdListenerActive = false
            Log.i(TAG, "Discovery stopped: $serviceType")
            runOnUiThread {
                status.text ="Discovery stopped"
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdListenerActive = false
            nsdManager.stopServiceDiscovery(this)
            runOnUiThread {
                status.text ="Discovery failed"
            }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdListenerActive = true
            nsdManager.stopServiceDiscovery(this)
            runOnUiThread {
                status.text ="Discovery failed"
            }
        }
    }

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Called when the resolve fails. Use the error code to debug.
            Log.e(TAG, "Resolve failed: $errorCode")
            runOnUiThread {
                status.text = "Service resolve failed!"
            }
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.e(TAG, "Resolve Succeeded. $serviceInfo")

            mService = serviceInfo
            mServicePort = serviceInfo.port
            mServiceHost = serviceInfo.host
            runOnUiThread {
                status.text = "${mService.serviceType} resolved!"
            }
        }
    }

    fun sendmessagetoservice(){
        val sendexecutor = Executors.newFixedThreadPool(1)
        val receiveexecutor = Executors.newFixedThreadPool(1)
        val sendtext = findViewById<EditText>(R.id.sendmessage)

        udpSocket.soTimeout = 5000
        sendexecutor.execute {
            sendUdp(sendtext.text.toString())
        }
        receiveexecutor.execute {
            receiveudp()
        }
    }

    fun sendUdp(msg: String) {
        val buf = msg.toByteArray()
        if (this::mServiceHost.isInitialized) {
            val packet = DatagramPacket(buf, buf.size, mServiceHost,mServicePort)
            udpSocket.send(packet)
            runOnUiThread {
                status.text = "Message $msg send."
            }
        } else {
            mServiceHost = InetAddress.getByName("172.31.242.254")
            mServicePort = 5555
            val packet = DatagramPacket(buf, buf.size, mServiceHost,mServicePort)
            udpSocket.send(packet)
            runOnUiThread {
                status.text = "Message $msg send to default host."
            }
        }
    }

    fun receiveudp() {
        val rectext = findViewById<TextView>(R.id.msg)
        val message = ByteArray(1024)
        val packet = DatagramPacket(message, message.size)
        try {
            udpSocket.receive(packet)
            val text = String(message, 0, packet.length)
            runOnUiThread {
                rectext.text = "Received: $text"
            }
        } catch (e: IOException) {
            runOnUiThread {
                rectext.text = "Received: ${e.message}"
            }
        } catch (e: SocketTimeoutException) {
            runOnUiThread {
                rectext.text = "Received: ${e.message}"
            }
        } catch (e: SocketException) {
            rectext.text = "Received: ${e.message}"
        }
    }

    @RequiresApi(33)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermissions()
        //initUDP()
        udpSocket = DatagramSocket()
        val connect = findViewById<Button>(R.id.connect)
        connect.setOnClickListener {
            connectPktCubeWifi()
        }
        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        status = findViewById(R.id.status)

        val send = findViewById<Button>(R.id.sendpacket)
        send.setOnClickListener {
            sendmessagetoservice()
        }
        val mdns = findViewById<Button>(R.id.mDNS)
        mdns.setOnClickListener {
            status.text = "Scanning for services..."
            //discover services
            discoverService()
        }

        val iptext = findViewById<TextView>(R.id.ip)

        val interfaces = NetworkInterface.getNetworkInterfaces()
        val list = interfaces.toList()
        for (element in list) {
            if (element.name.equals("wlan0")) {
                iptext.text = element.inetAddresses.toList().get(1).hostAddress
            }
        }
    }

    fun discoverService() {
        if (nsdListenerActive) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
        while (nsdListenerActive) {
            Thread.sleep(100)
        }
        nsdManager.discoverServices(SERVICE_TYPE,NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    @RequiresApi(33)
    private fun checkPermissions() {
        val REQUEST_CODE_ASK_PERMISSIONS = 1
        val missingPermissions: MutableList<String> = ArrayList()
        // check all required dynamic permissions
        for (permission in REQUIRED_SDK_PERMISSIONS) {
            val result = ContextCompat.checkSelfPermission(this, permission)
            if (result != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission)
            }
        }
        if (missingPermissions.isNotEmpty()) {
            // request all missing permissions
            val permissions = missingPermissions
                .toTypedArray()
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_ASK_PERMISSIONS)
        } else {
            val grantResults = IntArray(REQUIRED_SDK_PERMISSIONS.size)
            Arrays.fill(grantResults, PackageManager.PERMISSION_GRANTED)
            onRequestPermissionsResult(
                REQUEST_CODE_ASK_PERMISSIONS, REQUIRED_SDK_PERMISSIONS,
                grantResults
            )
        }
    }

    fun connectPktCubeWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val connManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = WifiNetworkSpecifier.Builder()
                .setSsid(SSID)
                .build()
            val networkReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(wifi)
                .build()

            val networkCallback = object : NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    network.bindSocket(udpSocket)
                    runOnUiThread {
                        status.text = "Connected to $SSID"
                        val iptext = findViewById<TextView>(R.id.ip)

                        val interfaces = NetworkInterface.getNetworkInterfaces()
                        val list = interfaces.toList()
                        for (element in list) {
                            if (element.name.equals("wlan0")) {
                                iptext.text = element.inetAddresses.toList()[1].hostAddress
                            }
                        }
                    }
                }

                override fun onUnavailable() {
                    super.onUnavailable()
                    runOnUiThread {
                        status.text = "NOT connected to $SSID"
                    }

                }
            }
            connManager.requestNetwork(networkReq, networkCallback)
        } else {
            TODO("VERSION.SDK_INT < Q")
        }
    }
}

