package com.backlogbattlers.app.domain.model

// how a user has set up their profile on this phone: the picture they picked (null shows the initial
// of their name). the picture is also saved to the account, so friends see it. the two switches for
// which sections friends get to see are not here: they are settings, kept with the account's
// settings so they follow the user (see UserSettings.showBadges and showShowcase)
data class ProfileCustomization(
    val pictureId: String? = null,
)
//------------------------------EOF------------------------------\\
