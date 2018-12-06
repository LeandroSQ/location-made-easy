package leandro.soares.quevedo.locationmadeeasy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.util.Log;

import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public final class LocationHelper {

	//<editor-fold desc="Constants" defaultstate="collapsed">
	public static final int REQUEST_CODE = 13301;
	private static final long ONE_MINUTE = 1000 * 60;
	private static final long TWO_MINUTES = ONE_MINUTE * 2;
	private static final long DISTANCE_FOR_UPDATES = 10;// Meters
	//</editor-fold>

	private Fragment fragment;
	private String currentTask;
	private Context context;
	private Location bestLocation;
	private OnLocationUpdateListener listener;
	private CustomDialogHandler customEnableProvidersDialog;
	private static long lastProviderRequestTime;

	private LocationMinifiedListener gpsListener, networkListener, passiveListener;

	private long beginRequestTime;

	private OnLocationTimeoutListener timeoutListener;
	private int timeoutTime;

	private static final boolean DEBUG_MODE = false;

	public LocationHelper (Context context, OnLocationUpdateListener listener) {
		this.context = context;
		this.listener = listener;
	}

	public LocationHelper (Fragment fragment, OnLocationUpdateListener listener) {
		this.fragment = fragment;
		this.context = fragment.getContext ();
		this.listener = listener;
	}

	public LocationHelper (Activity activity, OnLocationUpdateListener listener) {
		this.context = activity;
		this.listener = listener;
	}

	//<editor-fold defaultstate="collapsed" desc="User interface">

	/**
	 * Check location provider FINE granted
	 */
	public boolean checkLocationProviders () {
		// Check the location manager
		LocationManager locationManager = getLocationManager ();
		if (locationManager == null) {
			return false;
		}

		// Getting GPS status
		boolean isGPSEnabled = locationManager.isProviderEnabled (LocationManager.GPS_PROVIDER);
		// Getting network status
		boolean isNetworkEnabled = locationManager.isProviderEnabled (LocationManager.NETWORK_PROVIDER);

		// If none location provider enabled, request user to enable them
		if (!isGPSEnabled && !isNetworkEnabled) {
			showLocationSettings ();
			return false;
		} else if (isGPSEnabled && !isNetworkEnabled) {
			// Call user's attention to enable network provider
			return !showEnableProvidersDialog ();
		} else {
			// We got at least one provider enabled, return true
			return true;
		}
	}

	/**
	 * Check permissions and automatically request them
	 **/
	public boolean checkPermissions () {
		// If we don't have any permission granted
		if (isPermissionDenied (Manifest.permission.ACCESS_FINE_LOCATION) || isPermissionDenied (Manifest.permission.ACCESS_COARSE_LOCATION)) {
			// Request the GPS location permissions
			requestPermissions ();

			return false;
		} else {
			// Next step is to check if we got at least one provider enabled
			return checkLocationProviders ();
		}
	}

	private boolean checkPermissionsAndProviders () {
		if (isPermissionDenied (Manifest.permission.ACCESS_FINE_LOCATION) || isPermissionDenied (Manifest.permission.ACCESS_COARSE_LOCATION)) {
			return false;
		} else {
			// Check the location manager
			LocationManager locationManager = getLocationManager ();

			if (locationManager == null) {
				return false;
			}

			// Getting GPS status
			boolean isGPSEnabled = locationManager.isProviderEnabled (LocationManager.GPS_PROVIDER);
			// Getting network status
			boolean isNetworkEnabled = locationManager.isProviderEnabled (LocationManager.NETWORK_PROVIDER);

			// If none location provider enabled, request user to enable them
			return isGPSEnabled || isNetworkEnabled;
		}
	}

	/**
	 * Verifies if app is able to show permission grant dialog
	 **/
	private boolean checkPermissionRationale (String permission) {
		return ActivityCompat.shouldShowRequestPermissionRationale (getCallingActivity (this.context), permission);
	}

	/**
	 * Request the GPS and Network permissions on Activity and Fragment
	 **/
	private void requestPermissions () {
		if (this.fragment != null) {
			fragment.requestPermissions (new String[] {
					Manifest.permission.ACCESS_FINE_LOCATION,
					Manifest.permission.ACCESS_COARSE_LOCATION
			}, REQUEST_CODE);
		} else {
			ActivityCompat.requestPermissions (getCallingActivity (this.context), new String[] {
					Manifest.permission.ACCESS_FINE_LOCATION,
					Manifest.permission.ACCESS_COARSE_LOCATION
			}, REQUEST_CODE);
		}
	}

	public boolean handlePermissionsResult (int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CODE) {

			try {
				if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
					// Try to continue the current task (PS: the task does the permission check again)
					continueCurrentTask ();
				} else {
					if (checkPermissionRationale (Manifest.permission.ACCESS_FINE_LOCATION) && checkPermissionRationale (Manifest.permission.ACCESS_COARSE_LOCATION)) {
						requestPermissions ();
					} else {
						showApplicationSettings ();
						listener.onLocationRequestError ("Permissão negada");
					}
				}
			} catch (Exception e) {
				listener.onLocationRequestError (e.getMessage ());
			}

			return true;
		} else {
			return false;
		}
	}

	public boolean handleActivityResult (int requestCode, int resultCode, @Nullable Intent data) {
		if (requestCode == REQUEST_CODE) {

			// Try to continue the current task, only if we got now valid permissions and providers
			if (checkPermissionsAndProviders ()) {
				continueCurrentTask ();
			}

			return true;
		} else {
			return false;
		}
	}

	/**
	 * Begins the process of retrieving user's location
	 **/
	public void requestCurrentLocation () {
		currentTask = "requestCurrentLocation";

		// Check the permissions
		if (checkPermissions ()) {
			startLocationRequest ();
		}
	}

	/**
	 * Begins the process of retrieving user's location, with time limit
	 *
	 * @param timeout  The limit time (In millis)
	 * @param callback The timeout callback
	 **/
	public boolean requestCurrentLocationWithTimeout (int timeout, OnLocationTimeoutListener callback) {
		currentTask = "requestCurrentLocationWithTimeout";
		// Sets the timeout listener
		this.timeoutListener = callback;
		this.timeoutTime = timeout;

		// Check the permissions
		if (!checkPermissions ()) {
			return false;
		}

		// Starts the request
		if (startLocationRequest ()) {
			return false;
		}

		// If we are on debug mode, ignore the updates
		//if (DEBUG_MODE) return;

		// Starts a task after the timeout specified time
		Timer timer = new Timer ();
		timer.schedule (new TimerTask () {
			@Override
			public void run () {
				// If we don't disposed the timeoutListener after the timeout, cancel the request!
				if (timeoutListener != null) {
					// Calls the listener, we have an timeout event
					timeoutListener.onLocationTimedOut ();
				}
			}
		}, timeout);

		return true;
	}

	/**
	 * Get the saved best location
	 **/
	public Location getBestLocation () {
		return this.bestLocation;
	}

	public void setCustomEnableProvidersDialog (CustomDialogHandler customEnableProvidersDialog) {
		this.customEnableProvidersDialog = customEnableProvidersDialog;
	}

	//</editor-fold>

	//<editor-fold defaultstate="collapsed" desc="Internal utils">
	private void showLocationSettings () {
		if (fragment != null) {
			// On fragments we need to call directly
			fragment.startActivityForResult (new Intent (Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE);
		} else {
			// Just call the startActivityForResult in the specified Activity
			getCallingActivity (context).startActivityForResult (new Intent (Settings.ACTION_LOCATION_SOURCE_SETTINGS), REQUEST_CODE);
		}
	}

	private void showApplicationSettings () {
		Intent intent = new Intent ();
		intent.setAction (Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
		intent.addCategory (Intent.CATEGORY_DEFAULT);
		intent.setData (Uri.parse ("package:" + context.getPackageName ()));
		intent.addFlags (Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.addFlags (Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
		context.startActivity (intent);
	}

	private boolean showEnableProvidersDialog () {
		try {
			// Validation to show this dialog only once per minute
			if (System.currentTimeMillis () - lastProviderRequestTime >= ONE_MINUTE) {
				lastProviderRequestTime = System.currentTimeMillis ();

				// If the user specified the custom dialog handler
				// Show default dialog
				AlertDialog dialog = new AlertDialog.Builder (this.getContext ())
						.setTitle ("Atenção")
						.setMessage ("Para melhor resultados, gostaria de ativar a localização em Alta precisão?")
						.setPositiveButton ("sim", new DialogInterface.OnClickListener () {
							@Override
							public void onClick (DialogInterface dialogInterface, int i) {
								// Call the system's location settings screen
								showLocationSettings ();
							}
						})
						.setNegativeButton ("Não", new DialogInterface.OnClickListener () {
							@Override
							public void onClick (DialogInterface dialogInterface, int i) {
								// Continue the location request
								continueCurrentTask ();
							}
						})
						.show ();

				if (customEnableProvidersDialog != null) {
					customEnableProvidersDialog.onShow (dialog);
				}

				return true;
			} else {
				// Return false, because none dialog was showed
				return false;
			}
		} catch (Exception e) {
			// On error, simple ignore it
			e.printStackTrace ();
			return false;
		}
	}

	/**
	 * Extracts the calling activity from the specified context
	 **/
	private Activity getCallingActivity (Context context) {
		if (context == null) {
			// If we are on a fragment, get it's parent
			if (fragment != null) {
				return fragment.getActivity ();
			} else {
				// Return null we don't have an Activity
				return null;
			}
		} else if (context instanceof ContextWrapper) {
			if (context instanceof Activity) {
				// We are on an Activity
				return (Activity) context;
			} else {
				// We are on an Activity's child
				return getCallingActivity (((ContextWrapper) context).getBaseContext ());
			}
		}

		// Return null we don't have an Activity
		return null;
	}

	/**
	 * Retrieves true if a specified permission is denied
	 **/
	private boolean isPermissionDenied (String permission) {
		if (this.fragment != null) {
			// Is a fragment
			return ActivityCompat.checkSelfPermission (fragment.getContext (), permission) != PackageManager.PERMISSION_GRANTED;
		} else {// Otherwise is a Activity
			return ActivityCompat.checkSelfPermission (context, permission) != PackageManager.PERMISSION_GRANTED;
		}
	}

	/**
	 * Sets the best location, verifying if it is really better than the old one and if so, calls the listener
	 **/
	private void setBestLocation (Location newLocation) {
		// If we got a better location, save it as current best location
		if (isBetterLocation (newLocation, this.bestLocation)) {
			this.bestLocation = newLocation;
		}

		// Calculate the elapsed time
		this.bestLocation.getExtras ().putLong ("requestTime", System.currentTimeMillis () - this.beginRequestTime);

		// Reset the current task
		this.currentTask = null;

		// If the flag is true, ignore any updates!
		if (this.listener != null) {
			// If we had a valid update, just ignore the timeout listener.
			this.timeoutListener = null;
			// And call the locationUpdated event!
			listener.onLocationRetrieved (this.bestLocation);
		}
	}

	private void continueCurrentTask () {
		// Null check
		if (this.currentTask.isEmpty () || this.currentTask == null) return;

		switch (this.currentTask) {
			case "requestCurrentLocation":
				requestCurrentLocation ();
				break;
			case "requestCurrentLocationWithTimeout":
				requestCurrentLocationWithTimeout (this.timeoutTime, this.timeoutListener);
				break;
		}
	}

	/**
	 * Checks whether two providers are the same
	 **/
	private boolean isSameProvider (String provider1, String provider2) {
		return provider1 == null ? provider2 == null : provider1.equals (provider2);
	}

	/**
	 * Method to determine the best location between two Location objects
	 **/
	private boolean isBetterLocation (Location location, Location currentBestLocation) {
		if (currentBestLocation == null) {
			// A new location is always better than no location
			return true;
		}

		// Check whether the new location fix is newer or older
		long timeDelta = location.getTime () - currentBestLocation.getTime ();
		boolean isSignificantlyNewer = timeDelta > TWO_MINUTES;
		boolean isSignificantlyOlder = timeDelta < -TWO_MINUTES;
		boolean isNewer = timeDelta > 0;

		// If it's been more than two minutes since the current location, use the new location,
		// because the user has likely moved.
		if (isSignificantlyNewer) {
			return true;
			// If the new location is more than two minutes older, it must be worse.
		} else if (isSignificantlyOlder) {
			return false;
		}

		// Check whether the new location fix is more or less accurate
		int accuracyDelta = (int) (location.getAccuracy () - currentBestLocation.getAccuracy ());
		boolean isLessAccurate = accuracyDelta > 0;
		boolean isMoreAccurate = accuracyDelta < 0;
		boolean isSignificantlyLessAccurate = accuracyDelta > 200;

		// Check if the old and new location are from the same provider
		boolean isFromSameProvider = isSameProvider (location.getProvider (), currentBestLocation.getProvider ());

		// Determine location quality using a combination of timeliness and accuracy
		if (isMoreAccurate) {
			return true;
		} else if (isNewer && !isLessAccurate) {
			return true;
		} else {
			return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
		}
	}

	/**
	 * Retrieve the location manager service, if available
	 *
	 * @return The location manager
	 **/
	private LocationManager getLocationManager () {
		// Request system service
		LocationManager locationManager = (LocationManager) this.getContext ().getSystemService (Context.LOCATION_SERVICE);

		// Check if we got a valid Service
		if (locationManager == null) {
			listener.onLocationRequestError ("Ocorreu um erro inesperado, por favor, tente novamente mais tarde!");
		}

		return locationManager;
	}
	//</editor-fold>

	//<editor-fold defaultstate="collapsed" desc="Location handling">

	/**
	 * Begins the process of retrieving user's location
	 **/
	@SuppressLint ("MissingPermission")
	private boolean startLocationRequest () {
		// Calls the user event
		this.listener.onLocationRequestStart ();

		// Check the location manager
		LocationManager locationManager = getLocationManager ();
		if (locationManager == null) {
			return false;
		}

		// Save the current time, to calculate the elapsed time after
		this.beginRequestTime = System.currentTimeMillis ();

		// Try to retrieve the last known location
		Location locationGPS = locationManager.getLastKnownLocation (LocationManager.GPS_PROVIDER);
		Location locationNetwork = locationManager.getLastKnownLocation (LocationManager.NETWORK_PROVIDER);

		// Verify the newest location update
		long locationTimeGPS = locationGPS != null ? locationGPS.getTime () : TWO_MINUTES + 1;
		long locationTimeNetwork = locationNetwork != null ? locationNetwork.getTime () : TWO_MINUTES + 1;
		long currentTime = new Date ().getTime ();

		if (locationTimeGPS - locationTimeNetwork > 0) {
			// If we got an old location, request new update
			if (currentTime - locationTimeGPS >= ONE_MINUTE || DEBUG_MODE) {
				requestLocationUpdates (locationManager);
			} else {
				Log.d ("LocationHelper", "GPS cached location " + locationGPS.getLatitude () + ", " + locationGPS.getLongitude () + " with precision " + locationGPS.getAccuracy ());
				// We got an valid and recent location, update it on the class
				setBestLocation (locationGPS);
			}
		} else {
			// If we got an old location, request new update
			if (currentTime - locationTimeNetwork >= ONE_MINUTE || DEBUG_MODE) {
				requestLocationUpdates (locationManager);
			} else {
				Log.d ("LocationHelper", "Network cached location " + locationNetwork.getLatitude () + ", " + locationNetwork.getLongitude () + " with precision " + locationNetwork
						.getAccuracy ());
				// We got an valid and recent location, update it on the class
				setBestLocation (locationNetwork);
			}
		}

		return true;
	}

	private void requestLocationUpdates (LocationManager locationManager) {
		try {
			// Getting GPS status
			boolean isGPSEnabled = locationManager.isProviderEnabled (LocationManager.GPS_PROVIDER);

			// Getting network status
			boolean isNetworkEnabled = locationManager.isProviderEnabled (LocationManager.NETWORK_PROVIDER);

			// Getting passive status
			boolean isPassiveEnabled = locationManager.isProviderEnabled (LocationManager.PASSIVE_PROVIDER);

			if (isGPSEnabled || isNetworkEnabled) {
				if (isGPSEnabled) {
					setupGpsLocationListener (locationManager);
				} else {
					Log.i ("LocationHelper", "GPS provider disabled");
					gpsListener = null;// Make gps listener invalid
				}

				if (isNetworkEnabled) {
					setupNetworkLocationListener (locationManager);
				} else {
					Log.i ("LocationHelper", "Network provider disabled");
					networkListener = null;// Make network listener invalid
				}
			} else {

				Log.i ("LocationHelper", "GPS provider disabled");
				Log.i ("LocationHelper", "Network provider disabled");

				if (isPassiveEnabled) {
					// Try to get as passive provider
					setupPassiveLocationListener (locationManager);
				} else {
					// Otherwise, none provider enabled. Show error
					listener.onLocationRequestError ("Não foi possível localizá-lo. GPS e Network inativos!");
				}
			}
		} catch (Exception e) {
			e.printStackTrace ();
			listener.onLocationRequestError ("Ocorreu um erro inesperado ao buscar sua localização, por favor tente novamente mais tarde!");
		}
	}

	@SuppressLint ("MissingPermission")
	private void setupGpsLocationListener (final LocationManager locationManager) {
		Log.d ("LocationHelper", "Starting gps provider...");
		// Setup the GPS listener
		gpsListener = new LocationMinifiedListener () {
			@Override
			public void onLocationChanged (Location location) {
				Log.d ("LocationHelper", "GPS retrieved location " + location.getLatitude () + ", " + location.getLongitude () + " with precision " + location.getAccuracy ());
				// Set current best location
				setBestLocation (location);
				// Dispose itself
				locationManager.removeUpdates (gpsListener);
				// Remove the other provider listener
				if (networkListener != null) {
					locationManager.removeUpdates (networkListener);
				}
			}
		};
		// Request the locationManager updates
		locationManager.requestLocationUpdates (
				LocationManager.GPS_PROVIDER,
				ONE_MINUTE,
				DISTANCE_FOR_UPDATES,
				gpsListener
		);
	}

	@SuppressLint ("MissingPermission")
	private void setupNetworkLocationListener (final LocationManager locationManager) {
		Log.d ("LocationHelper", "Starting network provider...");
		// Setup the GPS listener
		networkListener = new LocationMinifiedListener () {
			@Override
			public void onLocationChanged (Location location) {
				Log.d ("LocationHelper", "Network retrieved location " + location.getLatitude () + ", " + location.getLongitude () + " with precision " + location.getAccuracy ());
				// Set current best location
				setBestLocation (location);
				// Dispose itself
				locationManager.removeUpdates (networkListener);
				// Remove the other provider listener
				if (gpsListener != null) {
					locationManager.removeUpdates (gpsListener);
				}
			}
		};
		// Request the locationManager updates
		locationManager.requestLocationUpdates (
				LocationManager.NETWORK_PROVIDER,
				ONE_MINUTE,
				DISTANCE_FOR_UPDATES,
				networkListener
		);
	}

	@SuppressLint ("MissingPermission")
	private void setupPassiveLocationListener (final LocationManager locationManager) {
		Log.d ("LocationHelper", "Starting passive provider...");
		// Setup the passive listener
		passiveListener = new LocationMinifiedListener () {
			@Override
			public void onLocationChanged (Location location) {
				Log.d ("LocationHelper", "Passive retrieved location " + location.getLatitude () + ", " + location.getLongitude () + " with precision " + location.getAccuracy ());
				// Set current best location
				setBestLocation (location);
				// Dispose itself
				locationManager.removeUpdates (passiveListener);
			}
		};
		// Request the locationManager updates
		locationManager.requestLocationUpdates (
				LocationManager.PASSIVE_PROVIDER,
				ONE_MINUTE,
				DISTANCE_FOR_UPDATES,
				passiveListener
		);
	}
	//</editor-fold>

	private Context getContext () {
		if (this.fragment != null) {
			return fragment.getContext ();
		} else {
			return context;
		}
	}

	/**
	 * The callback
	 **/
	public interface OnLocationUpdateListener {
		/**
		 * Called when the location started
		 **/
		void onLocationRequestStart ();

		/**
		 * Called when the location was finally provided
		 *
		 * @param location
		 **/
		void onLocationRetrieved (Location location);

		/**
		 * Called when an error occurs
		 *
		 * @param message
		 **/
		void onLocationRequestError (String message);
	}

	//<editor-fold defaultstate="collapsed" desc="Interfaces and anonymous classes">
	private abstract class LocationMinifiedListener implements LocationListener {
		@Override
		public void onStatusChanged (String s, int i, Bundle bundle) {
		}

		@Override
		public void onProviderEnabled (String s) {
		}

		@Override
		public void onProviderDisabled (String s) {
		}
	}

	/**
	 * The timeout listener
	 */
	public interface OnLocationTimeoutListener {
		void onLocationTimedOut ();
	}

	public interface CustomDialogHandler {
		void onShow (AlertDialog dialog);
	}
	//</editor-fold>

}
