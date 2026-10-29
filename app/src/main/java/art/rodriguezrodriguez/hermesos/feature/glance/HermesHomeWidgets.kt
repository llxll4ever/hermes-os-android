package art.rodriguezrodriguez.hermesos.feature.glance

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import art.rodriguezrodriguez.hermesos.MainActivity
import art.rodriguezrodriguez.hermesos.core.model.BotLiveState
import art.rodriguezrodriguez.hermesos.core.storage.WorkspacePreferencesStore

private val StartVoiceKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_START_VOICE)
private val TargetScopeKey = ActionParameters.Key<String>(MainActivity.EXTRA_FOCUS_SCOPE)

private val GlanceBg = Color(0xFF0A101C)
private val GlanceCard = Color(0xFF131E32)
private val GlanceTeal = Color(0xFF00E5C3)
private val GlanceRose = Color(0xFFFF4D6D)
private val GlanceAmber = Color(0xFFFFB020)
private val GlanceEmerald = Color(0xFF10B981)
private val GlanceText = Color(0xFFF1F5F9)
private val GlanceSubtle = Color(0xFF94A3B8)

// ── 1. Home-Screen Widget: Hermes Voice Mic + Quick Dispatch ──────────────────

class HermesMicWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HermesMicGlanceWidget()
}

class HermesMicGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceBg)
                    .cornerRadius(18.dp)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .background(GlanceCard)
                        .cornerRadius(14.dp)
                        .padding(10.dp)
                        .clickable(
                            actionStartActivity<MainActivity>(
                                actionParametersOf(StartVoiceKey to true)
                            )
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎙️",
                        style = TextStyle(fontSize = 22.sp)
                    )
                    Spacer(GlanceModifier.width(10.dp))
                    Column {
                        Text(
                            text = "Hablar con Hermes OS",
                            style = TextStyle(
                                color = ColorProvider(GlanceTeal),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            text = "Toca para dictar orden a bot o equipo",
                            style = TextStyle(
                                color = ColorProvider(GlanceSubtle),
                                fontSize = 11.sp
                            )
                        )
                    }
                }

                Spacer(GlanceModifier.height(8.dp))

                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Text(
                        text = "⚡ @orchestrator",
                        modifier = GlanceModifier
                            .background(GlanceCard)
                            .cornerRadius(10.dp)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(TargetScopeKey to "bot:orchestrator")
                                )
                            ),
                        style = TextStyle(color = ColorProvider(GlanceText), fontSize = 11.sp)
                    )
                    Spacer(GlanceModifier.width(6.dp))
                    Text(
                        text = "🏠 @domo",
                        modifier = GlanceModifier
                            .background(GlanceCard)
                            .cornerRadius(10.dp)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(
                                        TargetScopeKey to "bot:domo",
                                        StartVoiceKey to true
                                    )
                                )
                            ),
                        style = TextStyle(color = ColorProvider(GlanceRose), fontSize = 11.sp)
                    )
                }
            }
        }
    }
}

// ── 2. Home-Screen Widget: Hermes Live Bot Wall ───────────────────────────────

class HermesWallWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HermesWallGlanceWidget()
}

class HermesWallGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val bots = WorkspacePreferencesStore(context).readCachedBotWallForHomeWidgets().take(5)

        provideContent {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(GlanceBg)
                    .cornerRadius(18.dp)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .clickable(actionStartActivity<MainActivity>()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HERMES BOT WALL",
                        style = TextStyle(
                            color = ColorProvider(GlanceTeal),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = "Abrir Desktop →",
                        style = TextStyle(
                            color = ColorProvider(GlanceSubtle),
                            fontSize = 11.sp
                        )
                    )
                }

                Spacer(GlanceModifier.height(8.dp))

                bots.forEach { bot ->
                    val badgeColor = when (bot.liveState) {
                        BotLiveState.THINKING, BotLiveState.SPEAKING -> GlanceTeal
                        BotLiveState.RUNNING_TOOL -> GlanceAmber
                        BotLiveState.WAITING_APPROVAL, BotLiveState.ERROR -> GlanceRose
                        BotLiveState.IDLE -> GlanceEmerald
                    }
                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable(
                                actionStartActivity<MainActivity>(
                                    actionParametersOf(TargetScopeKey to "bot:${bot.profile}")
                                )
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "●",
                            style = TextStyle(color = ColorProvider(badgeColor), fontSize = 11.sp)
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = "@${bot.handle}",
                            style = TextStyle(
                                color = ColorProvider(GlanceText),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = bot.currentActivity.take(30),
                            style = TextStyle(
                                color = ColorProvider(GlanceSubtle),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
