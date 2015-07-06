package de.uni_kl.informatik.disco.discowall.firewallService.rules;

import android.util.Log;

import java.util.HashMap;
import java.util.LinkedList;

import de.uni_kl.informatik.disco.discowall.firewallService.FirewallExceptions;
import de.uni_kl.informatik.disco.discowall.packages.Connections;
import de.uni_kl.informatik.disco.discowall.packages.Packages;
import de.uni_kl.informatik.disco.discowall.utils.shell.ShellExecuteExceptions;

public class FirewallRulesManager {
    private static final String LOG_TAG = FirewallRulesManager.class.getSimpleName();
    public enum FirewallMode { FILTER_TCP, FILTER_UDP, FILTER_ALL, ALLOW_ALL, BLOCK_ALL }
    public enum FirewallPolicy { ALLOW, BLOCK, INTERACTIVE }

    //    private final HashMap<String, Connections.Connection> connectionIdToConnectionMap = new HashMap<>();
    private final RulesHash rulesHash = new RulesHash();
    private final FirewallIptableRulesHandler iptablesRulesHandler;
    private FirewallMode firewallMode;
    private FirewallPolicy firewallUnknownConnectionPolicy;

    public FirewallRulesManager(FirewallIptableRulesHandler iptablesRulesHandler) {
        this.iptablesRulesHandler = iptablesRulesHandler;
        firewallMode = FirewallMode.FILTER_TCP;
        firewallUnknownConnectionPolicy = FirewallPolicy.INTERACTIVE;
    }

    public FirewallPolicy getFirewallPolicy() {
        return firewallUnknownConnectionPolicy;
    }

    public void setFirewallPolicy(FirewallPolicy policy) throws FirewallExceptions.FirewallException {
        Log.i(LOG_TAG, "changing firewall policy: " + firewallUnknownConnectionPolicy + " --> " + policy);
        // it is allowed to re-apply the same policy, so that it is possible to re-write the iptable rule

        try {
            switch(policy) {
                case ALLOW:
                    iptablesRulesHandler.setDefaultPackageHandlingMode(FirewallIptableRulesHandler.PackageHandlingMode.ACCEPT_PACKAGE);
                    break;
                case BLOCK:
                    iptablesRulesHandler.setDefaultPackageHandlingMode(FirewallIptableRulesHandler.PackageHandlingMode.REJECT_PACKAGE);
                    break;
                case INTERACTIVE:
                    iptablesRulesHandler.setDefaultPackageHandlingMode(FirewallIptableRulesHandler.PackageHandlingMode.INTERACTIVE);
                    break;
            }
        } catch (ShellExecuteExceptions.ShellExecuteException e) {
            throw new FirewallExceptions.FirewallException("Unable to change firewall policy du to error: " + e.getMessage(), e);
        }

        firewallUnknownConnectionPolicy = policy;
    }

    public FirewallMode getFirewallMode() {
        return firewallMode;
    }

    public void setFirewallMode(FirewallMode firewallMode) {
        this.firewallMode = firewallMode;
    }

    public boolean isPackageAccepted(Packages.TransportLayerPackage tlPackage, Connections.Connection connection) {
        switch(firewallMode) {
            case ALLOW_ALL:     // MODE: accept all packages
                return true;

            case BLOCK_ALL:     // MODE: block all packages
                return false;

            case FILTER_TCP:    // MODE: filter tcp only, accept rest
                if (tlPackage.getProtocol() != Packages.TransportProtocol.TCP)
                    return true; // package is not tcp ==> will not be filtered
                else
                    break;

            case FILTER_UDP:    // MODE: filter udp only, accept rest
                if (tlPackage.getProtocol() != Packages.TransportProtocol.UDP)
                    return true;
                else
                    break;

            case FILTER_ALL:    // MODE: filter any package
                break;
        }

//        switch(tlPackage.getProtocol())
//        {
//            case TCP:
//                return isFilteredTcpPackageAccepted((Packages.TcpPackage) tlPackage, (Connections.TcpConnection) connection);
//            case UDP:
//                return isFilteredUdpPackageAccepted((Packages.UdpPackage) tlPackage, (Connections.UdpConnection) connection);
//            default:
//                Log.e(LOG_TAG, "Unsupported Protocol: " + tlPackage.getProtocol());
//                return true;
//        }

        return isFilteredPackageAccepted(tlPackage, connection);
    }

    private boolean isFilteredPackageAccepted(Packages.TransportLayerPackage tlPackage, Connections.Connection connection) {
        Log.i(LOG_TAG, "filtering package: " + tlPackage);

        return true;
    }

    public FirewallRules.FirewallTransportRule createTcpRule(int userId, Packages.IpPortPair sourceFilter, Packages.IpPortPair destinationFilter, FirewallRules.DeviceFilter deviceFilter, FirewallRules.RulePolicy rulePolicy) throws ShellExecuteExceptions.CallException, ShellExecuteExceptions.ReturnValueException {
        FirewallRules.FirewallTransportRule rule = new FirewallRules.FirewallTransportRule(userId, sourceFilter, destinationFilter, deviceFilter, rulePolicy);

        try {
            iptablesRulesHandler.addTcpConnectionRule(userId, new Packages.SourceDestinationPair(sourceFilter, destinationFilter), rulePolicy, deviceFilter);
        } catch (ShellExecuteExceptions.ShellExecuteException e) {
            // Remove created rule (if any), when an exception occurrs:
            iptablesRulesHandler.deleteTcpConnectionRule(userId, new Packages.SourceDestinationPair(sourceFilter, destinationFilter), rulePolicy, deviceFilter);
            throw e;
        }

        rulesHash.getRulesForUser(userId).add(rule);
        return rule;
    }

    public FirewallRules.FirewallTransportRedirectRule createTcpRedirectionRule(int userId, Packages.IpPortPair sourceFilter, Packages.IpPortPair destinationFilter, FirewallRules.DeviceFilter deviceFilter, Packages.IpPortPair redirectTo) throws FirewallRuleExceptions.InvalidRuleDefinitionException {
        FirewallRules.FirewallTransportRedirectRule rule = new FirewallRules.FirewallTransportRedirectRule(userId, sourceFilter, destinationFilter, deviceFilter, redirectTo);

        // TODO

        rulesHash.getRulesForUser(userId).add(rule);
        return rule;
    }

//    private boolean isFilteredUdpPackageAccepted(Packages.UdpPackage udpPackage, Connections.UdpConnection connection) {
//        return true;
//    }
//
//    private boolean isFilteredTcpPackageAccepted(Packages.TcpPackage tcpPackage, Connections.TcpConnection connection) {
//        Log.i(LOG_TAG, "Connection: " + connection);
//
//        return true;
//    }

    private class RulesHash {
        private final HashMap<Integer, LinkedList<FirewallRules.IFirewallRule>> userIdToRulesListHash = new HashMap<>();

        public RulesHash() {
        }

        public LinkedList<FirewallRules.IFirewallRule> getRulesForUser(int userId) {
            LinkedList<FirewallRules.IFirewallRule> rules = userIdToRulesListHash.get(userId);

            if (rules == null) {
                rules = new LinkedList<>();
                userIdToRulesListHash.put(userId, rules);
            }

            return rules;
        }

    }
}
