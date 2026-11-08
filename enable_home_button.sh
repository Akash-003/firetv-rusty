#!/bin/sh
# Turn on Rusty's accessibility service on the TV: the remote's Menu (☰) button opens Rusty,
# and Rusty replaces Amazon's home once after each boot. (Fire OS won't let its launcher be disabled.)
cd "$(dirname "$0")"
# clear first: forces Android to unbind and rebind the service even if the value hasn't changed
./tv sh 'settings put secure enabled_accessibility_services ""'
./tv sh 'settings put secure enabled_accessibility_services dev.akash.rusty/dev.akash.rusty.HomeKeyService'
./tv sh 'settings put secure accessibility_enabled 1'
./tv sh 'settings get secure enabled_accessibility_services'
