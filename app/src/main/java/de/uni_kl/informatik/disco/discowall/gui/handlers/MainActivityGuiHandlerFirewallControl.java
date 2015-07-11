package de.uni_kl.informatik.disco.discowall.gui.handlers;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.Toast;

import java.util.LinkedList;
import java.util.List;

import de.uni_kl.informatik.disco.discowall.MainActivity;
import de.uni_kl.informatik.disco.discowall.R;
import de.uni_kl.informatik.disco.discowall.firewall.Firewall;
import de.uni_kl.informatik.disco.discowall.firewall.FirewallExceptions;
import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallRulesManager;
import de.uni_kl.informatik.disco.discowall.gui.dialogs.ErrorDialog;

public class MainActivityGuiHandlerFirewallControl {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private final MainActivity mainActivity;

    public MainActivityGuiHandlerFirewallControl(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    public void onFirewallSwitchCheckedChanged(CompoundButton buttonView, final boolean isChecked) {
        actionSetFirewallEnabled(isChecked);
    }

    public void actionSetFirewallEnabled(final boolean enabled) {
        try {
            if (enabled && mainActivity.firewall.isFirewallRunning()) {
                return;
            } else if (!enabled && !mainActivity.firewall.isFirewallRunning()) {
                return;
            }
        } catch (Exception e) {
            new AlertDialog.Builder(mainActivity)
                    .setTitle("Firewall ERROR")
                    .setMessage("Firewall could not fetch state due to error: " + e.getMessage())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            e.printStackTrace();

            return;
        }

        final String actionName, message;
        if (enabled) {
            actionName = "Enable Firewall";
            message = "adding iptable rules...";
        } else {
            actionName = "Disable Firewall";
            message = "removing iptable rules...";
        }

        class FirewallSetupTask extends AsyncTask<Boolean, Object, Boolean> implements Firewall.FirewallEnableProgressListener, Firewall.FirewallDisableProgressListener {
            private AlertDialog.Builder errorAlert;
            private ProgressDialog progressDialog;
            private final PackageManager packageManager = mainActivity.getPackageManager();

            class IptablesCommandUpdate {
                String command;
            }
            class WatchedAppsUpdateBeforeRestore {
                List<ApplicationInfo> watchedApps;
            }
            class WatchedAppsUpdateRestoreApp {
                ApplicationInfo watchedApp;
                int appIndex;
            }
            class FirewallPolicyUpdate {
                FirewallRulesManager.FirewallPolicy policy;
            }

            @Override
            protected void onPreExecute() {
                super.onPreExecute();

                progressDialog = new ProgressDialog(mainActivity);
                progressDialog.setTitle(actionName);
                progressDialog.setIcon(R.drawable.firewall_launcher);

                if (enabled) {
                    progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                    progressDialog.setMax(1);
                } else {
                    progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                }

                progressDialog.setCancelable(false);
                progressDialog.setIndeterminate(true);
                progressDialog.setMessage(message);

                progressDialog.show();
            }

            protected void onPostExecute(Boolean result) {
                if (errorAlert != null) {
                    errorAlert.show();
                    return;
                }

                if (enabled)
                    Toast.makeText(mainActivity, "Firewall Enabled.", Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(mainActivity, "Firewall Disabled.", Toast.LENGTH_LONG).show();

                // If this action for changing the firewall-state has been called by code only
                // (i.e. not as reaction to the user changing the state via gui),
                // then the firewall-switch will differ from the firewall state.
                // (This happens as the firewall-state is restored on firewall-app-launch)
                // Therefore the switch is set here:
                ((Switch) mainActivity.findViewById(R.id.switchFirewallEnabled)).setChecked(enabled);

                // Gui-Update actions etc.
                onAfterFirewallEnabledStateChanged(enabled);

                // Hide Busy-Dialog
                progressDialog.dismiss();
            }

            @Override
            protected Boolean doInBackground(Boolean... params) {
                if (enabled) {
                    int port = mainActivity.discowallSettings.getFirewallPort(mainActivity);

                    try {
                        mainActivity.firewall.enableFirewall(port, this);
                    } catch (Exception e) {
                        errorAlert = new AlertDialog.Builder(mainActivity)
                                .setTitle("Firewall ERROR")
                                .setMessage("Firewall could not start due to error: " + e.getMessage())
                                .setIcon(android.R.drawable.ic_dialog_alert);
                        e.printStackTrace();
                    }
                } else {
                    try {
                        mainActivity.firewall.disableFirewall(this);
                    } catch (Exception e) {
                        errorAlert = new AlertDialog.Builder(mainActivity)
                                .setTitle("Firewall ERROR")
                                .setMessage("Firewall could not stop correctly due to error: " + e.getMessage())
                                .setIcon(android.R.drawable.ic_dialog_alert);
                        e.printStackTrace();
                    }
                }

                return null;
            }

            @Override
            protected void onProgressUpdate(Object... values) {
                super.onProgressUpdate(values);

                Object value = values[0];

                if (enabled) {
                    // Enabling Firewall actions

                    if (value instanceof IptablesCommandUpdate) {
                        IptablesCommandUpdate commandUpdate = (IptablesCommandUpdate) value;

                        progressDialog.setMessage("creating iptables structure...\n\n" + "iptables " + commandUpdate.command);
                    } else if (value instanceof WatchedAppsUpdateBeforeRestore) {
                        WatchedAppsUpdateBeforeRestore watchedAppsUpdateBeforeRestore = (WatchedAppsUpdateBeforeRestore) value;

                        progressDialog.setMessage("restoring monitored apps...");
                        progressDialog.setIndeterminate(true);
                        progressDialog.setMax(watchedAppsUpdateBeforeRestore.watchedApps.size());
                        progressDialog.setProgress(0);
                    } else if (value instanceof WatchedAppsUpdateRestoreApp) {
                        WatchedAppsUpdateRestoreApp updateRestoreApp = (WatchedAppsUpdateRestoreApp) value;

                        ApplicationInfo appInfo = updateRestoreApp.watchedApp;
                        String appName = appInfo.loadLabel(packageManager) + "";

                        progressDialog.setMessage("monitoring apps...\n\n" + appName + "\n" + appInfo.packageName);
                        progressDialog.setIndeterminate(false);
                        progressDialog.setProgress(updateRestoreApp.appIndex + 1);
                        progressDialog.setIcon(appInfo.loadIcon(packageManager));
                    } else if (value instanceof FirewallPolicyUpdate) {
                        FirewallPolicyUpdate firewallPolicyUpdate = (FirewallPolicyUpdate) value;

                        progressDialog.setIndeterminate(true);
                        progressDialog.setMessage("applying firewall-policy...\n\nPolicy: " + firewallPolicyUpdate.policy);
                        progressDialog.setIcon(R.drawable.firewall_launcher);
                    } else {
                        Log.d(LOG_TAG, "Unknown update-action: " + value);
                    }
                } else {
                    // Disabling Firewall actions

                    if (value instanceof IptablesCommandUpdate) {
                        IptablesCommandUpdate commandUpdate = (IptablesCommandUpdate) value;

                        progressDialog.setMessage("removing iptables structure...\n\n" + "iptables " + commandUpdate.command);
                    } else {
                        Log.d(LOG_TAG, "Unknown update-action: " + value);
                    }
                }
            }

            @Override
            public void onWatchedAppsBeforeRestore(List<ApplicationInfo> watchedApps) {
                WatchedAppsUpdateBeforeRestore watchedAppsUpdateBeforeRestore = new WatchedAppsUpdateBeforeRestore();
                watchedAppsUpdateBeforeRestore.watchedApps = new LinkedList<>(watchedApps);

                publishProgress(watchedAppsUpdateBeforeRestore);
            }

            @Override
            public void onWatchedAppsRestoreApp(ApplicationInfo watchedApp, int appIndex) {
                WatchedAppsUpdateRestoreApp updateRestoreApp = new WatchedAppsUpdateRestoreApp();
                updateRestoreApp.watchedApp = watchedApp;
                updateRestoreApp.appIndex = appIndex;

                publishProgress(updateRestoreApp);
            }

            @Override
            public void onFirewallPolicyBeforeApplyPolicy(FirewallRulesManager.FirewallPolicy policy) {
                FirewallPolicyUpdate firewallPolicyUpdate = new FirewallPolicyUpdate();
                firewallPolicyUpdate.policy = policy;

                publishProgress(firewallPolicyUpdate);
            }

            @Override
            public void onIptablesCommandBeforeExecute(String command) {
                // Used for both: ENABLING/DISABLING firewall progress

                IptablesCommandUpdate commandUpdate = new IptablesCommandUpdate();
                commandUpdate.command = command;

                publishProgress(commandUpdate);
            }

            @Override
            public void onIptablesCommandAfterExecute(String command) {
                // do nothing here
            }
        }

        // Store enabled/disabled state in settings, so that it can be restored on app-start
        mainActivity.discowallSettings.setFirewallEnabled(mainActivity, enabled);

        new FirewallSetupTask().execute();
    }

    /**
     * Is being called after the firewall has been enabled or disabled.
     *
     * @param firewallEnabled
     */
    private void onAfterFirewallEnabledStateChanged(boolean firewallEnabled) {
        Log.v(LOG_TAG, "Firewall enabled-state changed.");

        // Select RadioButton matching current Firewall Policy and Enable/Disable RadioButtons
        updateFirewallPolicyRadioButtonsWithCurrentPolicy();
    }

    public void showFirewallEnabledState() {
        Switch firewallEnabledSwitch = (Switch) mainActivity.findViewById(R.id.switchFirewallEnabled);
        try {
            firewallEnabledSwitch.setChecked(mainActivity.firewall.isFirewallRunning());
        } catch (Exception e) {
            new AlertDialog.Builder(mainActivity)
                    .setTitle("Firewall ERROR")
                    .setMessage("Firewall determine firewall state: " + e.getMessage())
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .show();
            e.printStackTrace();

            firewallEnabledSwitch.setChecked(false); // assuming firewall is NOT running
        }
    }

    public void updateFirewallPolicyRadioButtonsWithCurrentPolicy() {
        RadioButton buttonAllow = (RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeAllow);
        RadioButton buttonBlock = (RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeBlock);
        RadioButton buttonInteractive = (RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeInteractive);

        boolean firewallRunning = mainActivity.firewall.isFirewallRunning();

        // Buttons can only be used, if firewall is running
        buttonAllow.setEnabled(firewallRunning);
        buttonBlock.setEnabled(firewallRunning);
        buttonInteractive.setEnabled(firewallRunning);

        if (firewallRunning) {
            switch (mainActivity.firewall.getFirewallPolicy()) {
                case ALLOW:
                    buttonAllow.setChecked(true);
                    break;
                case BLOCK:
                    buttonBlock.setChecked(true);
                    break;
                case INTERACTIVE:
                    buttonInteractive.setChecked(true);
                    break;
            }
        }
    }

    public void setupFirewallPolicyRadioButtons() {
        // Bind on-check events:
        setupFirewallPolicyRadioButton((RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeAllow), FirewallRulesManager.FirewallPolicy.ALLOW);
        setupFirewallPolicyRadioButton((RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeBlock), FirewallRulesManager.FirewallPolicy.BLOCK);
        setupFirewallPolicyRadioButton((RadioButton) mainActivity.findViewById(R.id.radioButtonFirewallModeInteractive), FirewallRulesManager.FirewallPolicy.INTERACTIVE);
    }

    private void setupFirewallPolicyRadioButton(RadioButton button, final FirewallRulesManager.FirewallPolicy associatedFirewallPolicy) {
        button.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (!isChecked) // This method is also being called on the "un-check" event
                    return;

                // No policy-update required if the current-policy is the same as the requested one:
                if (mainActivity.firewall.getFirewallPolicy() == associatedFirewallPolicy)
                    return;

                // Since this operation takes around a second, it is anoying that the GUI freezes for this amount of time ==> parallel task
                new AsyncTask<Boolean, Boolean, Boolean>() {
                    private String errorMessage;

                    @Override
                    protected Boolean doInBackground(Boolean... params) {
                        Log.v(LOG_TAG, "Change firewall-policy to " + associatedFirewallPolicy);

                        try {
                            mainActivity.firewall.setFirewallPolicy(associatedFirewallPolicy);
                        } catch (FirewallExceptions.FirewallException e) {
                            errorMessage = "Error changing policy: " + e.getMessage();
                        }

                        return true;
                    }

                    @Override
                    protected void onPostExecute(Boolean aBoolean) {
                        super.onPostExecute(aBoolean);

                        if (errorMessage != null)
                            ErrorDialog.showError(mainActivity, "Firewall Policy", errorMessage);

                        Toast.makeText(mainActivity, "Firewall-Policy: " + mainActivity.firewall.getFirewallPolicy(), Toast.LENGTH_SHORT).show();
                    }
                }.execute();
            }
        });
    }

}