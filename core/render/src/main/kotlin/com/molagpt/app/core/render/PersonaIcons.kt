package com.molagpt.app.core.render

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiObjects
import androidx.compose.material.icons.filled.Engineering
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Work
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Shared persona icon registry. Persist only the string key; Compose screens resolve it here.
 */
object PersonaIcons {

    private val map = linkedMapOf(
        "default" to Icons.AutoMirrored.Filled.Chat,
        "assistant" to Icons.Filled.SmartToy,
        "person" to Icons.Filled.Person,
        "idea" to Icons.Filled.Lightbulb,
        "write" to Icons.Filled.Create,
        "copywriting" to Icons.Filled.Description,
        "code" to Icons.Filled.Code,
        "engineering" to Icons.Filled.Engineering,
        "debug" to Icons.Filled.Build,
        "translate" to Icons.Filled.Language,
        "teacher" to Icons.Filled.School,
        "learn" to Icons.AutoMirrored.Filled.MenuBook,
        "analysis" to Icons.Filled.DataUsage,
        "math" to Icons.Filled.Calculate,
        "science" to Icons.Filled.Science,
        "art" to Icons.Filled.Palette,
        "design" to Icons.Filled.Brush,
        "music" to Icons.Filled.MusicNote,
        "game" to Icons.Filled.SportsEsports,
        "travel" to Icons.Filled.TravelExplore,
        "explore" to Icons.Filled.Explore,
        "business" to Icons.Filled.Business,
        "work" to Icons.Filled.Work,
        "law" to Icons.Filled.Gavel,
        "medical" to Icons.Filled.LocalHospital,
        "health" to Icons.Filled.Favorite,
        "fitness" to Icons.Filled.FitnessCenter,
        "support" to Icons.Filled.SupportAgent,
        "headphones" to Icons.Filled.Headphones,
        "psychology" to Icons.Filled.Psychology,
        "mindfulness" to Icons.Filled.SelfImprovement,
        "flight" to Icons.Filled.Flight,
        "vision" to Icons.Filled.Visibility,
        "star" to Icons.Filled.Star,
        "check" to Icons.Filled.CheckCircle,
        "help" to Icons.AutoMirrored.Filled.HelpOutline,
        "emoji_objects" to Icons.Filled.EmojiObjects,
    )

    val entries: List<Pair<String, ImageVector>> get() = map.toList()

    fun resolve(name: String?): ImageVector = map[name] ?: Icons.AutoMirrored.Filled.Chat

    const val DEFAULT_ICON = "assistant"
}
