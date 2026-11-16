package com.kiyoscripts.nexusclone.view.fake


import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import top.niunaijun.blackbox.entity.location.BLocation
import com.kiyoscripts.nexusclone.R
import com.kiyoscripts.nexusclone.databinding.ActivityOsmdroidBinding
import com.kiyoscripts.nexusclone.util.inflate
import com.kiyoscripts.nexusclone.util.toast


/**
 *
 * @Author: BlackBoxing
 * @CreateDate: 2022/3/14
 */
class FollowMyLocationOverlay : AppCompatActivity() {

    companion object {
        private const val TAG = "FollowMyLocationOverlay"
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 1

        /**
         * ACCESS_FINE_LOCATION is declared by the Bcore library manifest, which is merged
         * into this app, so it is requestable here. ACCESS_COARSE_LOCATION is requested
         * alongside it because Android 12+ lets the user grant only approximate location.
         */
        private val LOCATION_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }

    private val binding: ActivityOsmdroidBinding by inflate()

    lateinit var startPoint: GeoPoint

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //handle permissions first, before map is created. not depicted here

        //load/initialize the osmdroid configuration, this can be done
        // This won't work unless you have imported this: org.osmdroid.config.Configuration.*
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        //setting this before the layout is inflated is a good idea
        //it 'should' ensure that the map has a writable location for the map cache, even without permissions
        //if no tiles are displayed, you can try overriding the cache path using Configuration.getInstance().setCachePath
        //see also StorageUtils
        //note, the load method also sets the HTTP User Agent to your application's package name, if you abuse osm's
        //tile servers will get you banned based on this string.

        //inflate and create the map
        setContentView(binding.root)

        // Nothing used to call requestPermissions() outside the result callback, so the
        // permission flow never started at all. Ask here, over a live window.
        ensureLocationPermissions()

        val location: BLocation? = intent.getParcelableExtra("location")

        startPoint = if (location == null) {
            GeoPoint(30.2736, 120.1563)
        } else {
            GeoPoint(location.latitude, location.longitude)
        }


        val startMarker = Marker(binding.map)
        startMarker.position = startPoint
        startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

        binding.map.overlays.add(startMarker)
        val mReceive: MapEventsReceiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                startPoint = p
                startMarker.position = p
                startMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                binding.map.overlays.add(startMarker)
                toast(p.latitude.toString() + " - " + p.longitude)
                return false
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                return false
            }
        }
        binding.map.overlays.add(MapEventsOverlay(mReceive))
        val mapController = binding.map.controller
        mapController.setZoom(12.5)
//        val startPoint = GeoPoint(30.2736, 120.1563)
        mapController.setCenter(startPoint)
        binding.map.setTileSource(TileSourceFactory.MAPNIK)
    }

    override fun onBackPressed() {
        finishWithResult(startPoint)
    }

    override fun onResume() {
        super.onResume()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this)
        //Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        binding.map.onResume() //needed for compass, my location overlays, v6.0.0 and up
    }

    override fun onPause() {
        super.onPause()
        //this will refresh the osmdroid configuration on resuming.
        //if you make changes to the configuration, use
        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this)
        //Configuration.getInstance().save(this, prefs)
        binding.map.onPause()  //needed for compass, my location overlays, v6.0.0 and up
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS_REQUEST_CODE) return

        // Only re-ask for what was actually denied, and only while the system still lets
        // us ask. The previous version copied every permission straight back into the
        // request list without ever reading grantResults, so a denial re-triggered this
        // callback, which re-requested, forever: an undismissable dialog storm that left
        // the Activity unusable.
        val retry = ArrayList<String>()
        for (i in permissions.indices) {
            val denied = i >= grantResults.size ||
                grantResults[i] == PackageManager.PERMISSION_DENIED
            if (!denied) continue
            // false after a denial means "don't ask again" (or a policy denial): the
            // system will not show the dialog, so re-requesting would be a no-op loop.
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
                retry.add(permissions[i])
            } else {
                Log.w(TAG, "location permission permanently denied: ${permissions[i]}")
            }
        }

        if (retry.isEmpty()) {
            // Degraded, not fatal: the map still pans, zooms and returns the tapped point,
            // which is all this screen needs. Only the "centre on my position" convenience
            // is gone, so do not finish() the Activity.
            Log.w(TAG, "no location permission granted, continuing without it")
            this.toast(getString(R.string.location_permission_denied))
            return
        }
        this.toast(getString(R.string.location_permission_needed))
        ActivityCompat.requestPermissions(
            this,
            retry.toTypedArray(),
            REQUEST_PERMISSIONS_REQUEST_CODE
        )
    }

    /**
     * Request the location permissions this screen needs, if any are still missing.
     *
     * No-op when everything is already granted, so it is safe to call on every create.
     */
    private fun ensureLocationPermissions() {
        val missing = LOCATION_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        ActivityCompat.requestPermissions(
            this,
            missing.toTypedArray(),
            REQUEST_PERMISSIONS_REQUEST_CODE
        )
    }

    private fun finishWithResult(geoPoint: GeoPoint) {
        intent.putExtra("latitude", geoPoint.latitude)
        intent.putExtra("longitude", geoPoint.longitude)
        setResult(Activity.RESULT_OK, intent)
        val imm: InputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        window.peekDecorView()?.run {
            imm.hideSoftInputFromWindow(windowToken, 0)
        }
        finish()
    }

}
