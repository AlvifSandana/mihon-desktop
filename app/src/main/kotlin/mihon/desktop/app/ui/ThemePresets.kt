package mihon.desktop.app.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * A hand-tuned theme preset: a name plus explicit light and dark Material
 * color schemes. The id is what gets persisted in AppPreferences
 * (KEY_THEME_PRESET); unknown ids fall back to Default.
 */
data class ThemePreset(
    val id: String,
    val name: String,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    fun scheme(isDark: Boolean): ColorScheme = if (isDark) dark else light
}

/**
 * The selectable theme presets (Mihon-inspired names). Colors are reasonable
 * approximations, not exact Mihon parity.
 */
val themePresets: List<ThemePreset> = listOf(
    ThemePreset(
        id = "default",
        name = "Default",
        light = lightColorScheme(),
        dark = darkColorScheme(),
    ),
    ThemePreset(
        id = "lavender",
        name = "Lavender",
        light = lightScheme(
            primary = Color(0xFF7A5BE8), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFE7DEFF), onPrimaryContainer = Color(0xFF24005A),
            secondary = Color(0xFF625B71), tertiary = Color(0xFF7D5260),
            background = Color(0xFFFDF8FF), onBackground = Color(0xFF1D1A22),
            surface = Color(0xFFFDF8FF), onSurface = Color(0xFF1D1A22),
        ),
        dark = darkScheme(
            primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF3F2E96),
            primaryContainer = Color(0xFF553FA8), onPrimaryContainer = Color(0xFFE7DEFF),
            secondary = Color(0xFFCCC2DC), tertiary = Color(0xFFEFB8C8),
            background = Color(0xFF141218), onBackground = Color(0xFFE6E0E9),
            surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9),
        ),
    ),
    ThemePreset(
        id = "strawberry_daiquiri",
        name = "Strawberry Daiquiri",
        light = lightScheme(
            primary = Color(0xFFC2185B), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E4), onPrimaryContainer = Color(0xFF3E001D),
            secondary = Color(0xFF77565A), tertiary = Color(0xFF7D5260),
            background = Color(0xFFFFF8F8), onBackground = Color(0xFF22191B),
            surface = Color(0xFFFFF8F8), onSurface = Color(0xFF22191B),
        ),
        dark = darkScheme(
            primary = Color(0xFFFFB1C3), onPrimary = Color(0xFF5E0031),
            primaryContainer = Color(0xFF8E0049), onPrimaryContainer = Color(0xFFFFD9E4),
            secondary = Color(0xFFE6BDC0), tertiary = Color(0xFFEFB8C8),
            background = Color(0xFF1A1113), onBackground = Color(0xFFF1DEE2),
            surface = Color(0xFF1A1113), onSurface = Color(0xFFF1DEE2),
        ),
    ),
    ThemePreset(
        id = "midnight_dune",
        name = "Midnight Dune",
        light = lightScheme(
            primary = Color(0xFF8B5000), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDCBE), onPrimaryContainer = Color(0xFF2C1600),
            secondary = Color(0xFF755A48), tertiary = Color(0xFF605C7C),
            background = Color(0xFFFFF8F6), onBackground = Color(0xFF221A16),
            surface = Color(0xFFFFF8F6), onSurface = Color(0xFF221A16),
        ),
        dark = darkScheme(
            primary = Color(0xFFFFB877), onPrimary = Color(0xFF4E2800),
            primaryContainer = Color(0xFF6F3E00), onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFD7BFA8), tertiary = Color(0xFFC6C2E6),
            background = Color(0xFF17120F), onBackground = Color(0xFFECE0D8),
            surface = Color(0xFF17120F), onSurface = Color(0xFFECE0D8),
        ),
    ),
    ThemePreset(
        id = "green_apple",
        name = "Green Apple",
        light = lightScheme(
            primary = Color(0xFF386A20), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFB8F397), onPrimaryContainer = Color(0xFF072100),
            secondary = Color(0xFF55624C), tertiary = Color(0xFF386563),
            background = Color(0xFFF9FAEF), onBackground = Color(0xFF1A1C16),
            surface = Color(0xFFF9FAEF), onSurface = Color(0xFF1A1C16),
        ),
        dark = darkScheme(
            primary = Color(0xFF9CD67B), onPrimary = Color(0xFF133800),
            primaryContainer = Color(0xFF245100), onPrimaryContainer = Color(0xFFB8F397),
            secondary = Color(0xFFBCCBAA), tertiary = Color(0xFFA0D0CB),
            background = Color(0xFF10140E), onBackground = Color(0xFFE1E3D8),
            surface = Color(0xFF10140E), onSurface = Color(0xFFE1E3D8),
        ),
    ),
    ThemePreset(
        id = "teal_turkey",
        name = "Teal & Turkey",
        light = lightScheme(
            primary = Color(0xFF006A60), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFF9EF2E3), onPrimaryContainer = Color(0xFF00201C),
            secondary = Color(0xFF4A635F), tertiary = Color(0xFF9C4238),
            background = Color(0xFFF4FBF8), onBackground = Color(0xFF161D1B),
            surface = Color(0xFFF4FBF8), onSurface = Color(0xFF161D1B),
        ),
        dark = darkScheme(
            primary = Color(0xFF82D5C7), onPrimary = Color(0xFF003731),
            primaryContainer = Color(0xFF005048), onPrimaryContainer = Color(0xFF9EF2E3),
            secondary = Color(0xFFB1CCC6), tertiary = Color(0xFFFFB4A3),
            background = Color(0xFF0E1513), onBackground = Color(0xFFDEE4E1),
            surface = Color(0xFF0E1513), onSurface = Color(0xFFDEE4E1),
        ),
    ),
    ThemePreset(
        id = "tako",
        name = "Tako",
        light = lightScheme(
            primary = Color(0xFF0061A4), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFD1E4FF), onPrimaryContainer = Color(0xFF001D36),
            secondary = Color(0xFF535F70), tertiary = Color(0xFF6B5778),
            background = Color(0xFFF8FAFF), onBackground = Color(0xFF191C20),
            surface = Color(0xFFF8FAFF), onSurface = Color(0xFF191C20),
        ),
        dark = darkScheme(
            primary = Color(0xFF9FCAFF), onPrimary = Color(0xFF003158),
            primaryContainer = Color(0xFF00497D), onPrimaryContainer = Color(0xFFD1E4FF),
            secondary = Color(0xFFB9C7DC), tertiary = Color(0xFFD6BEE4),
            background = Color(0xFF101418), onBackground = Color(0xFFE1E2E8),
            surface = Color(0xFF101418), onSurface = Color(0xFFE1E2E8),
        ),
    ),
    ThemePreset(
        id = "yotsuba",
        name = "Yotsuba",
        light = lightScheme(
            primary = Color(0xFF8F4C00), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDCC2), onPrimaryContainer = Color(0xFF2E1500),
            secondary = Color(0xFF745C41), tertiary = Color(0xFF4C662B),
            background = Color(0xFFFFF8F5), onBackground = Color(0xFF211B14),
            surface = Color(0xFFFFF8F5), onSurface = Color(0xFF211B14),
        ),
        dark = darkScheme(
            primary = Color(0xFFFFB77C), onPrimary = Color(0xFF502600),
            primaryContainer = Color(0xFF703A00), onPrimaryContainer = Color(0xFFFFDCC2),
            secondary = Color(0xFFDCC3A4), tertiary = Color(0xFFB4D18B),
            background = Color(0xFF17120E), onBackground = Color(0xFFEBE0D3),
            surface = Color(0xFF17120E), onSurface = Color(0xFFEBE0D3),
        ),
    ),
)

/** Resolves a persisted preset id; unknown/missing ids fall back to Default. */
fun themePresetFor(id: String): ThemePreset = themePresets.firstOrNull { it.id == id }
    ?: themePresets.first()

/** Swatch colors for a preset's preview row: primary/tertiary/background, light then dark. */
fun ThemePreset.swatchColors(): List<Color> = listOf(
    light.primary, light.tertiary, light.background,
    dark.primary, dark.tertiary, dark.background,
)

/** Compact scheme builders so each preset above stays readable. */
private fun lightScheme(
    primary: Color, onPrimary: Color,
    primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, tertiary: Color,
    background: Color, onBackground: Color,
    surface: Color, onSurface: Color,
): ColorScheme = lightColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, tertiary = tertiary,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface,
)

private fun darkScheme(
    primary: Color, onPrimary: Color,
    primaryContainer: Color, onPrimaryContainer: Color,
    secondary: Color, tertiary: Color,
    background: Color, onBackground: Color,
    surface: Color, onSurface: Color,
): ColorScheme = darkColorScheme(
    primary = primary, onPrimary = onPrimary,
    primaryContainer = primaryContainer, onPrimaryContainer = onPrimaryContainer,
    secondary = secondary, tertiary = tertiary,
    background = background, onBackground = onBackground,
    surface = surface, onSurface = onSurface,
)
