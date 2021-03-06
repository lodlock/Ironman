package com.example.jihun.ironman;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.jihun.ironman.arduino.ArduinoConnector;
import com.example.jihun.ironman.arduino.BluetoothPairActivity;
import com.example.jihun.ironman.arduino.PacketParser;
import com.example.jihun.ironman.speech.CommandSpeechFilter;
import com.example.jihun.ironman.speech.EnhancedSpeechRecognizer;
import com.example.jihun.ironman.speech.SignalSpeechFilter;
import com.example.jihun.ironman.speech.SpeechListener;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private TextView txt_app_status_;
    private ProgressBar progress_bar_;
    private EnhancedSpeechRecognizer speech_recognizer_;
    private ArduinoConnector arduinoConnector_;
    private final AppStateManager app_status_manager_ = new AppStateManager();

    // The value for magnifying to display on progress bar.
    private final int kSpeechMagnifyingValue = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "start main");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setBackgroundColor();

        progress_bar_ = (ProgressBar)findViewById(R.id.progressBarSpeech);
        progress_bar_.setMax(normalizeSpeechValue(EnhancedSpeechRecognizer.kSpeechMaxValue));
        txt_app_status_ = (TextView) findViewById(R.id.textViewSpeachResult);
        updateStatusUIText(app_status_manager_.getStatus());

        speech_recognizer_ = buildSpeechRecognizer();
        arduinoConnector_ = new ArduinoConnector(arduino_listener_);
    }

    // Set gradient background color.
    private void setBackgroundColor() {
        View layout = findViewById(R.id.mainLayout);
        GradientDrawable gd = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0xFFF0FAFF,0xFFA3E0FF});
        gd.setCornerRadius(0f);
        layout.setBackground(gd);
    }

    /**
     * Build EnhancedSpeechRecognizer.
     *
     * In the process of building, it connects filters, such as command and signal filter, at the listener.
     * SpeechRecognizer will end up delivering only filtered events via its listener.
     *
     * - Listener chain order.
     * Listener of EnhancesSpeechRecognizer -> SignalSpeechFilter -> CommandSpeechFilter
     */
    private EnhancedSpeechRecognizer buildSpeechRecognizer() {
        /*
        Build listener chain in reverse order of event deliver order.
         */
        CommandSpeechFilter cmd_filter = new CommandSpeechFilter(speech_listener_);
        // Add commands that it will listen for.
        final Resources rs = getResources();
        cmd_filter.addPattern(rs.getString(R.string.command_lighton),
                rs.getString(R.string.command_lighton_variant));
        cmd_filter.addPattern(rs.getString(R.string.command_lightoff),
                rs.getString(R.string.command_lightoff_variant));

        SignalSpeechFilter signal_filter = new SignalSpeechFilter(cmd_filter, // connect cmd_filter.
                rs.getString(R.string.speech_singal));

        return new EnhancedSpeechRecognizer(this, speech_recognizer_listener_,
                signal_filter); // connect signal_filter.
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        arduinoConnector_.destroy();
        speech_recognizer_.destroy();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "onPause");
        super.onPause();
        speech_recognizer_.stop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        // Noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        if (resultCode == RESULT_OK) {
            BluetoothDevice device = intent.getParcelableExtra("device");
            arduinoConnector_.connect(device);
        }
    }

    // connection button listener.
    public void onPair(View v){
        Intent intent = new Intent(getApplicationContext(), BluetoothPairActivity.class);
        startActivityForResult(intent, 0);
    }

    // Handles the speeches delivered by EnhancedSpeechRecognizer.
    private SpeechListener speech_listener_ = new SpeechListener() {
        @Override
        public void onSpeechRecognized(ArrayList<String> recognitions) {
            if (recognitions.isEmpty()) {
                return;
            }
            // Use only the first command.
            String cmd = recognitions.get(0);
            Toast.makeText(getApplicationContext(), cmd, Toast.LENGTH_SHORT).show();

            try {
                arduinoConnector_.send(cmd);
            } catch (Exception e) {
                e.printStackTrace();
                throw e;
            }
        }
    };

    /**
     * Change the value, ranged from -2.12 to 10, into new value ranged from 0 to 1212.
     * @param value speech level from SpeechRecognizer.
     * @return normalized value.
     */
    private int normalizeSpeechValue(float value) {
        return (int)((value + Math.abs(EnhancedSpeechRecognizer.kSpeechMinValue))
                * kSpeechMagnifyingValue);
    }

    /**
     * Listener for speech recognition.
     */
    private EnhancedSpeechRecognizer.Listener speech_recognizer_listener_ =
        new EnhancedSpeechRecognizer.Listener() {
            @Override
            public void onStart() {
                app_status_manager_.updateSpeechRecognitionStatus(true);
                updateStatusUIText(app_status_manager_.getStatus());
            }

            @Override
            public void onStop() {
                app_status_manager_.updateSpeechRecognitionStatus(false);
                updateStatusUIText(app_status_manager_.getStatus());
            }

            @Override
            public void onSoundChanged(float rmsdB) {
                final int increment = normalizeSpeechValue(rmsdB) - progress_bar_.getProgress();
                progress_bar_.incrementProgressBy(increment);
            }
    };

    /**
     * Listener for Arduino.
     */
    private ArduinoConnector.Listener arduino_listener_ = new ArduinoConnector.Listener() {
        @Override
        public void onConnect(BluetoothDevice device) {
            app_status_manager_.updateConnectionStatus(true);
            updateStatusUIText(app_status_manager_.getStatus());
            Toast.makeText(getApplicationContext(), "Connected", Toast.LENGTH_SHORT).show();

            // Starting recognition right after connection made.
            speech_recognizer_.start();
        }

        @Override
        public void onReaction(PacketParser.Type type, String data) {
            if (type == PacketParser.Type.ActivityDetected) {
                // There is a limitation that Android doesn't offer continuous speech recognition.
                // So only when is activity detected, speech recognition starts.
                speech_recognizer_.start();
            }
        }

        @Override
        public void onDisconnect(BluetoothDevice device) {
            app_status_manager_.updateConnectionStatus(false);
            updateStatusUIText(app_status_manager_.getStatus());
            Toast.makeText(getApplicationContext(), "Disconnected", Toast.LENGTH_SHORT).show();
        }
    };

    // Update Status Text on UI.
    public void updateStatusUIText(AppState state) {
        switch (state) {
            case Disconnected:
                txt_app_status_.setText(
                        getResources().getString(R.string.txtview_disconnected));
                break;
            case Standby:
                txt_app_status_.setText(getResources().getString(R.string.txtview_standby));
                break;
            case Listening:
                txt_app_status_.setText(
                        getResources().getString(R.string.txtview_listening));
                break;
        }
    }

    // Represent current application status.
    private enum AppState {
        Disconnected, // connected to Arduino.
        Standby,       // waiting until activity detected
        Listening,    // listening speech recognition.
    }

    /**
     * Manage current app status.
     * Evaluate application status using input status.
     */
    private class AppStateManager {
        private boolean connected_ = false;
        private boolean is_listening_ = false;

        /**
         * Update Arduino connection status.
         * @param connected true if connected to Arduino.
         * @return Current app status.
         */
        public AppState updateConnectionStatus(boolean connected) {
            connected_ = connected;
            return getStatus();
        }

        /**
         * Update speech recognition status.
         * @param is_listening true if speech recognition is working.
         * @return Current app status.
         */
        public AppState updateSpeechRecognitionStatus(boolean is_listening) {
            is_listening_ = is_listening;
            return getStatus();
        }

        /**
         * Evaluate the current AppStatus.
         * @return Current AppStatus.
         */
        private AppState getStatus() {
            if (connected_) {
                return is_listening_ ? AppState.Listening : AppState.Standby;
            } else {
                return  AppState.Disconnected;
            }
        }
    }
}
