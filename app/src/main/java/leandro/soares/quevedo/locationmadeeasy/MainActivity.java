package leandro.soares.quevedo.locationmadeeasy;

import android.app.ProgressDialog;
import android.content.Intent;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity implements LocationHelper.OnLocationUpdateListener {

	private TextView textView;
	private Button btnCaptureLocation;

	private LocationHelper locationHelper;

	private ProgressDialog progressDialog;

	@Override
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate (savedInstanceState);
		setContentView (R.layout.activity_main);

		loadComponents ();
	}

	private void loadComponents () {
		this.textView = findViewById (R.id.textView);
		this.btnCaptureLocation = findViewById (R.id.button);

		this.locationHelper = new LocationHelper (this, this);

		this.btnCaptureLocation.setOnClickListener (this::onCaptureLocationButtonClick);
	}

	private void showProgressIndicator () {
		// Disposes the last loader
		if (progressDialog != null && progressDialog.isShowing ()) {
			progressDialog.dismiss ();
		}
		// Show the loader
		progressDialog = ProgressDialog.show (this, "Carregando", "Requisitando sua localização", true);
	}

	private void hideProgressIndicator () {
		new Handler ().post (() -> {
			// Disposes the loader
			if (this.progressDialog != null) {
				this.progressDialog.dismiss ();
			}

			this.progressDialog = null;
		});
	}

	public void onCaptureLocationButtonClick (View view) {
		// Request the location using the library
		this.locationHelper.requestCurrentLocation ();
	}

	private String getTimeString (long elapsedTime) {
		if (elapsedTime > 1000 * 60) {// Minutes
			return String.valueOf (Math.round (elapsedTime / (1000 * 60))) + "m";
		} else if (elapsedTime > 1000) {// Seconds
			return String.valueOf (Math.round (elapsedTime / 1000)) + "s";
		} else {
			return String.valueOf (elapsedTime) + "ms";
		}
	}

	@Override
	protected void onDestroy () {
		// Avoid memory leaks
		hideProgressIndicator ();

		super.onDestroy ();
	}

	@Override
	public void onRequestPermissionsResult (int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult (requestCode, permissions, grantResults);

		if (locationHelper.handlePermissionsResult (requestCode, permissions, grantResults)) {
			// Ignore
		} else {
			// Do other validations
		}
	}

	@Override
	protected void onActivityResult (int requestCode, int resultCode, @Nullable Intent data) {
		super.onActivityResult (requestCode, resultCode, data);

		if (locationHelper.handleActivityResult (requestCode, resultCode, data)) {
			// Ignore
		} else {
			// Do other validations
		}
	}

	@Override
	public void onLocationRequestStart () {
		showProgressIndicator ();
	}

	@Override
	public void onLocationRetrieved (Location location) {
		hideProgressIndicator ();

		// Create simple log entry
		StringBuilder buffer = new StringBuilder (this.textView.getText ().toString ());
		buffer.append ("Location: {")
			  .append (location.getLatitude ())
			  .append (", ")
			  .append (location.getLongitude ())
			  .append ("} with ")
			  .append (location.getAccuracy ())
			  .append (" of precision. (")
			  .append (getTimeString (location.getExtras ().getLong ("requestTime")))
			  .append (")\n");

		this.textView.setText (buffer.toString ());
	}

	@Override
	public void onLocationRequestError (String message) {
		hideProgressIndicator ();

		// Show error message
		new AlertDialog.Builder (this)
				.setTitle ("Erro!")
				.setMessage (message)
				.setPositiveButton ("OK", null)
				.show ();
	}
}
