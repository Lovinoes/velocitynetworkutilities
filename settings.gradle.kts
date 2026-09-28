rootProject.name = "VelocityNetworkUtilities"

include(
    "networkutilitiescommon",
    "velocitynetworkutilities",
    "playeruuidcachevelocity",
    "velocitynetworkvanish",
    "velocitynetworkchat",
    "velocitynetworkplayerinfo",
    "velocitynetworkmoderation",
    "papernetworkvanish",
    "papernetworkchat"
)

project(":networkutilitiescommon").projectDir = file("networkutilitiescommon")
project(":velocitynetworkutilities").projectDir = file("velocitynetworkutilities")
project(":playeruuidcachevelocity").projectDir = file("playeruuidcachevelocity")
project(":velocitynetworkvanish").projectDir = file("velocitynetworkvanish")
project(":velocitynetworkchat").projectDir = file("velocitynetworkchat")
project(":velocitynetworkplayerinfo").projectDir = file("velocitynetworkplayerinfo")
project(":velocitynetworkmoderation").projectDir = file("velocitynetworkmoderation")
project(":papernetworkvanish").projectDir = file("papernetworkvanish")
project(":papernetworkchat").projectDir = file("papernetworkchat")
