package de.uni_kl.informatik.disco.discowall.netfilter.bridge;

import android.util.Log;

import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallRules;
import de.uni_kl.informatik.disco.discowall.firewall.rules.FirewallIptableRulesHandler;
import de.uni_kl.informatik.disco.discowall.netfilter.iptables.IptableConstants;
import de.uni_kl.informatik.disco.discowall.netfilter.iptables.IptablesControl;
import de.uni_kl.informatik.disco.discowall.packages.Connections;
import de.uni_kl.informatik.disco.discowall.packages.Packages;
import de.uni_kl.informatik.disco.discowall.utils.shell.ShellExecuteExceptions;

public class NetfilterFirewallRulesHandler implements FirewallIptableRulesHandler {
    private static final String LOG_TAG = FirewallIptableRulesHandler.class.getSimpleName();

    private NetfilterFirewallRulesHandler() { }
    public static final FirewallIptableRulesHandler instance = new NetfilterFirewallRulesHandler();

    public void addTcpRedirectionRule(int userID, Packages.IpPortPair destinationHostToRedirect, Packages.IpPortPair redirectTo, FirewallRules.DeviceFilter deviceFilter) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        // http://www.debuntu.org/how-to-redirecting-network-traffic-to-a-new-ip-using-iptables/
        // http://www.cyberciti.biz/faq/linux-port-redirection-with-iptables/

        String redirectionFilter = "";
        if (destinationHostToRedirect.hasIp())
            redirectionFilter += " --destination " + destinationHostToRedirect.getIp();
        if (destinationHostToRedirect.hasPort())
            redirectionFilter += " --destination-port " + destinationHostToRedirect.getPort();

        String rule = "-t nat PREROUTING -p tcp " + redirectionFilter + " -j DNAT --to-destination " + redirectTo.getIp() + ":" + redirectTo.getPort();

        // TODO:
        // 1) assert 'echo "1" > /proc/sys/net/ipv4/ip_forward'
        // 2) rule must exist "iptables -t nat -A POSTROUTING -j MASQUERADE"

        // TODO: Redirection does not work - neither on pc nor on android
        /* 1) When rule added to "-t nat -A OUTPUT" with "-j REDIRECT --to-port", the rule matches the outgoing package, but does not seem to edit the ports (according to wireshark)
           2) ANY rule added to "-t nat -A PREROUTING" does not even match.
         */

        addDeleteUserRule(userID, rule, deviceFilter, false);
    }

    public void addTcpConnectionRule(int userID, Connections.IConnection connection, FirewallRules.RulePolicy policy, FirewallRules.DeviceFilter deviceFilter) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        addDeleteTcpConnectionRule(userID, connection, policy, deviceFilter, false);
    }

    public void deleteTcpConnectionRule(int userID, Connections.IConnection connection, FirewallRules.RulePolicy policy, FirewallRules.DeviceFilter deviceFilter) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        addDeleteTcpConnectionRule(userID, connection, policy, deviceFilter, true);
    }

    private void addDeleteTcpConnectionRule(int userID, Connections.IConnection connection, FirewallRules.RulePolicy policy, FirewallRules.DeviceFilter deviceFilter, boolean delete) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        // Packages: Direction source => destination
        addDeleteUserConnectionRuleTcp(userID, connection.getSource(), connection.getDestination(), policy, deviceFilter, delete);

        // Packages: Direction destination => source
        addDeleteUserConnectionRuleTcp(userID, connection.getDestination(), connection.getSource(), policy, deviceFilter, delete);
    }

    private void addDeleteUserConnectionRuleTcp(int userID, Packages.IpPortPair source, Packages.IpPortPair destination, FirewallRules.RulePolicy policy, FirewallRules.DeviceFilter deviceFilter, boolean delete) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        String target;

        switch (policy) {
            case ACCEPT:
                target = NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_ACCEPT;
                break;
            case BLOCK:
                target = NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_REJECT;
                break;
            case INTERACTIVE:
                target = NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_INTERACTIVE;
                break;
            default:
                throw new RuntimeException("Unknown policy: " + policy);
        }

        String rule = "-p tcp";

        /*
           !!! IMPORTANT !!!
           ----------------
           NEVER use "localhost/127.0.0.1"
           * do NOT filter for localhost as "--source" or "--destination", as the packages sent by the device (or any other linux-box)
           * will NEVER contain localhost/127.0.0.1. Instead the packages will contain the address of the device within WiFi/UMTS.
           * Example: A package to 8.8.8.8 will contain a sender like "192.168.178.10" and the receiver 8.8.8.8.
           *          ==> Source is NOT localhost/127.0.0.1 as far as iptables is concerned.
           *
           * ==> localhost/127.0.0.1 will be ignored as filter, as they will never match and destroy the rules function.
         */

        // Source filtering:
        if (source.getPort() > 0)
            rule += " --source-port " + source.getPort();
        if (!source.getIp().isEmpty() && !source.getIp().equals("*") && !source.getIp().equals("localhost") && !source.getIp().equals("127.0.0.1"))
            rule += " --source " + source.getIp();

        // Destination filtering:
        if (destination.getPort() > 0)
            rule += " --destination-port " + destination.getPort();
        if (!destination.getIp().isEmpty() && !destination.getIp().equals("*") && !destination.getIp().equals("localhost") && !destination.getIp().equals("127.0.0.1"))
            rule += " --destination " + destination.getIp();

        // Append jump to target chain:
        rule += " -j " + target;

        addDeleteUserRule(userID, rule, deviceFilter, delete);
    }

    private void addDeleteUserRule(int userID, String rule, FirewallRules.DeviceFilter deviceFilter, boolean delete) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        String chain;

        switch (deviceFilter) {
            case WIFI:
                chain = NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_INTERFACE_WIFI;
                break;
            case UMTS_3G:
                chain = NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_INTERFACE_3G;
                break;
            case ANY:
                addDeleteUserRule(userID, rule, FirewallRules.DeviceFilter.WIFI, delete);
                addDeleteUserRule(userID, rule, FirewallRules.DeviceFilter.UMTS_3G, delete);
                return;
            default:
                throw new RuntimeException("Unknown device: " + deviceFilter);
        }

        rule = "-m owner --uid-owner " + userID + " " + rule;

        if (delete)
            IptablesControl.ruleDeleteIgnoreIfMissing(chain, rule);
        else
            IptablesControl.ruleAddIfMissing(chain, rule);
    }

    public boolean isMainChainJumpsEnabled() throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.NonZeroReturnValueException {
        return IptablesControl.ruleExists(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN)
                || IptablesControl.ruleExists(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN)
                || IptablesControl.ruleExists(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN)
                || IptablesControl.ruleExists(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
    }

    /**
     * Adds/Removes the rules forwarding packages to the firewall main-chain. Can be used "pause" the firewall.
     *
     * @param enableJumpsToMainChain
     * @throws ShellExecuteExceptions.CallException
     * @throws ShellExecuteExceptions.ReturnValueException
     */
    public void setMainChainJumpsEnabled(boolean enableJumpsToMainChain) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        if (enableJumpsToMainChain) {
            IptablesControl.ruleAddIfMissing(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
            IptablesControl.ruleAddIfMissing(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);

            IptablesControl.ruleAddIfMissing(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
            IptablesControl.ruleAddIfMissing(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
        } else {
            IptablesControl.ruleDeleteIgnoreIfMissing(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
            IptablesControl.ruleDeleteIgnoreIfMissing(IptableConstants.Chains.INPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);

            IptablesControl.ruleDeleteIgnoreIfMissing(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_TCP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
            IptablesControl.ruleDeleteIgnoreIfMissing(IptableConstants.Chains.OUTPUT, NetfilterBridgeIptablesHandler.RULE_UDP_JUMP_TO_FIREWALL_PREFILTER_CHAIN);
        }
    }

    private String[] getFirewallForwardingRulesForUser(int uid) {
        return new String[]{
                "-m owner --uid-owner " + uid + " -j MARK --set-mark " + (uid + NetfilterBridgeIptablesHandler.PACKAGE_UID_MARK_OFFSET), // rule which encodes the user-id as package-mark
                "-m owner --uid-owner " + uid + " -j " + NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN // rule which forwards package to firewall main-chain
        };
    }

    public boolean isUserPackagesForwardedToFirewall(int uid) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        int forwardedCount = 0;
        int notForwardedCount = 0;

        for (String rule : getFirewallForwardingRulesForUser(uid)) {
            if (IptablesControl.ruleExists(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN_PREFILTER, rule))
                forwardedCount++;
            else
                notForwardedCount++;
        }

        // Inconsistency in case only part of the rules are set
        if (forwardedCount > 0 && notForwardedCount > 0) {
            Log.e(LOG_TAG, "Inconsistent forwarding rule for user-packages found! Missing rules will be added.");
            setUserPackagesForwardToFirewall(uid, true);
            return true;
        }

        return (forwardedCount > 0);
    }

    public void setUserPackagesForwardToFirewall(int uid, boolean forward) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        if (forward) {
            for (String rule : getFirewallForwardingRulesForUser(uid))
                IptablesControl.ruleAddIfMissing(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN_PREFILTER, rule);

//        IptablesControl.ruleAddIfMissing(CHAIN_FIREWALL_MAIN_PREFILTER, "-m owner --uid-owner "+uid+" -j MARK --set-mark" + uid);
//        IptablesControl.ruleAddIfMissing(CHAIN_FIREWALL_MAIN_PREFILTER, "-m owner --uid-owner "+uid+" -j " + CHAIN_FIREWALL_MAIN);
        } else {
            for (String rule : getFirewallForwardingRulesForUser(uid))
                IptablesControl.ruleDeleteIgnoreIfMissing(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN_PREFILTER, rule);
        }
    }

    public PackageHandlingMode getDefaultPackageHandlingMode() throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.NonZeroReturnValueException {
        if (IptablesControl.ruleExists(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_ACCEPTED))
            return PackageHandlingMode.ACCEPT_PACKAGE;

        if (IptablesControl.ruleExists(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_REJECTED))
            return PackageHandlingMode.REJECT_PACKAGE;

        if (IptablesControl.ruleExists(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_INTERACTIVE))
            return PackageHandlingMode.INTERACTIVE;

        // This case cannot happen, as long as the iptables have not been edited from the outside
        return null;
    }

    public void setDefaultPackageHandlingMode(PackageHandlingMode mode) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        // Delete rule for current behavior
        IptablesControl.ruleDeleteIgnoreIfMissing(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_ACCEPTED);
        IptablesControl.ruleDeleteIgnoreIfMissing(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_REJECTED);
        IptablesControl.ruleDeleteIgnoreIfMissing(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_INTERACTIVE);

        // set new behavior
        switch (mode) {
            case ACCEPT_PACKAGE:
                IptablesControl.ruleAdd(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_ACCEPTED);
                return;
            case REJECT_PACKAGE:
                IptablesControl.ruleAdd(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_REJECTED);
                return;
            case INTERACTIVE:
                IptablesControl.ruleAdd(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, NetfilterBridgeIptablesHandler.RULE_JUMP_TO_FIREWALL_INTERACTIVE);
                return;
        }
    }

    public String getFirewallRulesText() throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.NonZeroReturnValueException {
        final String delim = "\n";
        String text = "";

        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN_PREFILTER, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_INTERACTIVE, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_ACCEPT, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_ACTION_REJECT, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_MAIN, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_INTERFACE_3G, true, true) + delim;
        text += IptablesControl.getRuleInfoText(NetfilterBridgeIptablesHandler.CHAIN_FIREWALL_INTERFACE_WIFI, true, true) + delim;

        return text;
    }
}