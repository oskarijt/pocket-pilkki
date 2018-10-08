package com.android.erkhal.pocket_pilkki

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.preference.PreferenceManager
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Toast
import com.android.erkhal.pocket_pilkki.fishingBook.FishingBookActivity
import com.android.erkhal.pocket_pilkki.global.GlobalFishSpecies
import com.android.erkhal.pocket_pilkki.model.CaughtFish
import com.android.erkhal.pocket_pilkki.persistence.FishDatabase
import com.android.erkhal.pocket_pilkki.utils.PersistCaughtFishAsyncTask
import com.android.erkhal.pocket_pilkki.utils.Utils
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import kotlinx.android.synthetic.main.activity_pilkki_ar.*
import kotlinx.android.synthetic.main.dialog_caught_fish_info.view.*
import java.util.*
import kotlin.collections.HashMap

// Constants
const val CATCHING_MODE_DURATION_MILLIS: Long = 1000
const val PROGRESS_INCREMENT_COOLDOWN = 1000
const val PROGRESS_DECREMENT_COOLDOWN = 200
const val COMPLETED_ONBOARDING_PREF_NAME = "completedOnboarding"

/**
 * This class represents the main gameplay activity, which consists of the AR camera view and the
 * needed UI buttons, and of course the fishing itself. The feedback used to inform the player about
 * the fish acting on the bait is made using vibration and sound.
 */
class PilkkiArActivity : AppCompatActivity(),
        AccelerometerController.AcceleroMeterControllerListener,
        FishingRunnable.OnFishGnawingListener {

    //This is toggled to true when the fish is gnawing the bait
    private var catchingModeOn = false

    //Toggled to true when the player clicks the plane and spawns the pond
    private var fishingModeOn = false

    //Renderables for the AR scene
    private lateinit var fishingPondRenderable: ModelRenderable
    private lateinit var fishRenderables: HashMap<Int, ModelRenderable>

    // AR scene anchors & nodes
    private lateinit var fishingPondAnchor: Anchor
    private var fishingPondNode: TransformableNode? = null

    // Augmented Reality Fragment for main gameplay
    private lateinit var arFragment: ArFragment

    //Controllers & Runnables
    private lateinit var accelerometerController: AccelerometerController
    private var fishingRunnable: FishingRunnable? = null

    //fishing progress
    private var advancement: Int = 0

    //Timestamp for cooldown counting
    private var mShakeTimestamp: Long = 0

    // The current fish to be caught is stored in this variable
    private var currentFish: CaughtFish? = null

    // Sound FX MediaPlayers
    private lateinit var reel_sound: MediaPlayer
    private lateinit var smallSplash_sound: MediaPlayer
    private lateinit var bigSplash_sound: MediaPlayer
    private lateinit var fail_sound: MediaPlayer
    private lateinit var collect_sound: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pilkki_ar)

        showOnBoardingIfNecessary()

        setupRenderables()

        arFragment = ar_fragment as ArFragment
        accelerometerController = AccelerometerController(this)
        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
                    spawnFishingPond(hitResult)
                    startCatching()
                }

        tvProgressBar.text = getString(R.string.progress_bar_finding_spot)

        // Media player initializations for sound FX
        reel_sound = MediaPlayer.create(this, R.raw.reeling)
        smallSplash_sound = MediaPlayer.create(this, R.raw.small_splash)
        bigSplash_sound = MediaPlayer.create(this, R.raw.big_splash)
        fail_sound = MediaPlayer.create(this, R.raw.fail)
        collect_sound = MediaPlayer.create(this, R.raw.collect)

        // Button click listeners
        btnMenu.setOnClickListener {
            val intent = Intent(this, MenuActivity::class.java)
            quitFishing()
            startActivity(intent)
        }

        arFragment.setOnTapArPlaneListener { hitResult, _, _ ->
            spawnFishingPond(hitResult)
            startCatching()
        }

        fishingBookButton.setOnClickListener {
            quitFishing()
            openFishingBook()
        }
    }

    // Shows the onboarding introduction if the player hasn't seen it yet
    private fun showOnBoardingIfNecessary() {
        PreferenceManager.getDefaultSharedPreferences(this).apply {
            if (!getBoolean(COMPLETED_ONBOARDING_PREF_NAME, false)) {
                // The player hasn't seen the OnboardingFragment yet, so show it
                startActivity(Intent(this@PilkkiArActivity, IntroductionActivity::class.java))
            }
        }
    }

    private fun openFishingBook() {
        val openFishingBookIntent = Intent(this, FishingBookActivity::class.java)
        startActivity(openFishingBookIntent)
    }

    private fun quitFishing() {
        if(fishingRunnable != null) {
            fishingRunnable?.quit()
        }
        fishingPondNode?.setParent(null)
        tvProgressBar.text = getString(R.string.progress_bar_finding_spot)
        arFragment.arSceneView.planeRenderer.isEnabled = true
        fishingModeOn = false
        catchingModeOn = false
    }

    // This function is called when the player jerks the phone at the right moment. Increments the
    // progress bar or decrements it, depending on the player's timing
    override fun onDeviceJerked() {
        val curTime = System.currentTimeMillis()

        if (catchingModeOn) {

            if (advancement >= 100) {
                fishCaught()
            }

            if (curTime > mShakeTimestamp + PROGRESS_INCREMENT_COOLDOWN) {
                if (advancement <= 99) {
                    mShakeTimestamp = curTime
                    advancement += 35
                    Log.d("ADV", "Progressbar: INCR $advancement")
                }
            }

        } else if (advancement < 100 && advancement > 0 && curTime > mShakeTimestamp + PROGRESS_DECREMENT_COOLDOWN) {
            advancement -= 10
            fail_sound.start()
            Log.d("ADV", "Progressbar: DECR $advancement")
        }
        fishingBar.progress = advancement
    }

    // Persists the caught fish into database using AsyncTask run in a worker thread
    private fun persistCaughtFish(caughtFish: CaughtFish) {
        val task = PersistCaughtFishAsyncTask(FishDatabase.get(this))
        task.execute(caughtFish)
    }

    // Vibrates the phone and plays a sound when the fish is gnawing the bait,
    // requiring the player to act on it.
    override fun onFishGnawing() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        catchingModeOn = true
        reel_sound.start()
        vibrator.vibrate(VibrationEffect.createOneShot(CATCHING_MODE_DURATION_MILLIS, VibrationEffect.DEFAULT_AMPLITUDE))
        this.runOnUiThread {
            Handler().postDelayed({ catchingModeOn = false }, CATCHING_MODE_DURATION_MILLIS)
        }
    }

    private fun startCatching() {
        fishingModeOn = true
        tvProgressBar.text = getString(R.string.progress_bar)
        tvProgressBar.setTextColor(getColor(R.color.material_deep_teal_200))
        resetGnawCounter()
        startFishingThread()
        rodView.setImageResource(R.drawable.fishingrod)

        // Get a random species of fish with randomized measurements
        currentFish = Utils.getRandomizedFish()
    }

    private fun fishCaught() {

        Toast.makeText(this, getString(R.string.dialog_fish_caught), Toast.LENGTH_SHORT).show()
        catchingModeOn = false
        fishingRunnable?.quit()
        currentFish?.caughtTimestamp = Date().time

        smallSplash_sound.start()

        // Take net into hand
        rodView.setImageResource(R.drawable.net)
        spawnFish()
    }

    private fun resetGnawCounter() {
        advancement = 0
        fishingBar.progress = advancement
    }

    private fun spawnFishingPond(hitResult: HitResult) {

        if (!fishingModeOn) {
            fishingPondAnchor = hitResult.createAnchor()
            val anchorNode = AnchorNode(fishingPondAnchor)
            anchorNode.setParent(arFragment.arSceneView.scene)
            fishingPondNode = TransformableNode(arFragment.transformationSystem)
            fishingPondNode?.setParent(anchorNode)

            disableViewNodeController(fishingPondNode!!)

            fishingPondNode?.renderable = fishingPondRenderable
            fishingPondNode?.select()

            // Disable plane rendering, since that is no longer needed.
            arFragment.arSceneView.planeRenderer.isEnabled = false
        }
    }

    // Spawns the correct fish when the player has caught one.
    // Also sets up the click listener for when the player clicks on the fish to collect it.
    private fun spawnFish() {
        val fishAnchor = fishingPondAnchor
        val anchorNode = AnchorNode(fishAnchor)
        anchorNode.setParent(arFragment.arSceneView.scene)
        val fishNode = TransformableNode(arFragment.transformationSystem)
        fishNode.setParent(anchorNode)

        fishNode.localPosition = Vector3(0f, 0.5f, 0f)
        fishNode.localRotation = Quaternion.axisAngle(Vector3(0f, 1.0f, 0f), 45f)

        disableViewNodeController(fishNode)

        fishNode.renderable = fishRenderables[currentFish?.species]
        fishNode.select()

        // Show dialog when the player taps on the fish
        fishNode.setOnTapListener { hitTestResult, motionEvent ->
            fishNode.setParent(null)
            showCaughtDialog()
            collect_sound.start()
        }
    }

    //Inflates the AlertDialog's view and populates the data in the fish layout
    private fun showCaughtDialog() {
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setCancelable(false)
        val inflatedFishView = layoutInflater.inflate(R.layout.dialog_caught_fish_info, null)
        dialogBuilder.setView(inflatedFishView)
        inflatedFishView.fish_image.setImageDrawable(getDrawable(Utils.getImageResource(currentFish!!)))
        inflatedFishView.fish_species.text = getString(currentFish?.species ?: R.string.fishspecies_pike)
        inflatedFishView.fish_measurements.text =
                getString(R.string.fish_measurements,
                        currentFish?.getFishLength(),
                        currentFish?.getFishWeight())

        dialogBuilder.setPositiveButton(R.string.fish_caught_positive) { _, _ ->
            persistCaughtFish(currentFish!!)
            startCatching()
        }
        dialogBuilder.setNegativeButton(R.string.fish_caught_negative) { _, _ ->
            currentFish = null
            startCatching()
        }

        dialogBuilder.create().show()
    }

    // This function disables the ARCore's built in ViewNode controllers, so the player
    // cannot scale, translate or rotate the pond.
    private fun disableViewNodeController(viewNode: TransformableNode) {
        viewNode.rotationController.isEnabled = false
        viewNode.scaleController.isEnabled = false
        viewNode.translationController.isEnabled = false
    }

    // Start the fishing thread, which generates the events of a fish gnawing the bait
    private fun startFishingThread() {
        fishingRunnable = FishingRunnable(this, 50)
        val fishingThread = Thread(fishingRunnable)
        fishingThread.start()
    }

    // Sets up all renderables needed in the AR view, such as all the fish and the pond
    private fun setupRenderables() {

        fishRenderables = HashMap()
        // A species knows it's Uri for the 3D model file,
        // so lets get each one and add them to our Map of Fish renderables
        GlobalFishSpecies.species.forEach { species ->
            val renderable = ModelRenderable.builder()
                    .setSource(this, species.modelFilepath)
                    .build()
            renderable.thenAccept {
                fishRenderables[species.speciesName] = it
            }
        }

        // This one is for the fishing pond
        val modelUri = Uri.parse("pond.sfb")
        val renderableFishingPond = ModelRenderable.builder()
                .setSource(this, modelUri)
                .build()
        renderableFishingPond.thenAccept { it ->
            fishingPondRenderable = it
        }
    }
}