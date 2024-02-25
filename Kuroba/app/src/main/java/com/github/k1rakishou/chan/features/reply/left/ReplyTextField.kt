package com.github.k1rakishou.chan.features.reply.left

import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.ContentAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.features.reply.data.ReplyLayoutState
import com.github.k1rakishou.chan.features.reply.data.ReplyLayoutVisibility
import com.github.k1rakishou.chan.ui.compose.components.KurobaComposeTextField
import com.github.k1rakishou.chan.ui.compose.components.KurobaLabelText
import com.github.k1rakishou.chan.ui.compose.freeFocusSafe
import com.github.k1rakishou.chan.ui.compose.providers.LocalChanTheme
import com.github.k1rakishou.chan.ui.compose.requestFocusSafe

@Composable
internal fun ReplyTextField(
  replyLayoutState: ReplyLayoutState,
  replyLayoutEnabled: Boolean
) {
  val chanTheme = LocalChanTheme.current
  val localSoftwareKeyboardController = LocalSoftwareKeyboardController.current

  var prevReplyLayoutVisibility by remember { mutableStateOf<ReplyLayoutVisibility>(ReplyLayoutVisibility.Collapsed) }
  val replyText by replyLayoutState.replyText
  val replyLayoutVisibility by replyLayoutState.replyLayoutVisibility
  val maxCommentLength by replyLayoutState.maxCommentLength

  val disabledAlpha = ContentAlpha.disabled

  val replyInputVisualTransformation = remember(key1 = chanTheme, key2 = replyLayoutEnabled, key3 = disabledAlpha) {
    return@remember VisualTransformation { text ->
      val spannedText = ReplyTextFieldHelpers.colorizeReplyInputText(
        disabledAlpha = disabledAlpha,
        text = text,
        replyLayoutEnabled = replyLayoutEnabled,
        chanTheme = chanTheme
      )

      return@VisualTransformation TransformedText(spannedText, OffsetMapping.Identity)
    }
  }

  val focusRequester = remember { FocusRequester() }

  LaunchedEffect(
    key1 = replyLayoutVisibility,
    block = {
      if (
        prevReplyLayoutVisibility == ReplyLayoutVisibility.Collapsed &&
        replyLayoutVisibility == ReplyLayoutVisibility.Opened
      ) {
        focusRequester.requestFocusSafe()
        localSoftwareKeyboardController?.show()
      } else if (
        prevReplyLayoutVisibility != ReplyLayoutVisibility.Collapsed &&
        replyLayoutVisibility == ReplyLayoutVisibility.Collapsed
      ) {
        // TODO: New reply layout
//                if (!globalUiInfoManager.isAnyReplyLayoutOpened()) {
//                    localSoftwareKeyboardController?.hide()
//                }
      }

      prevReplyLayoutVisibility = replyLayoutVisibility
    }
  )

  DisposableEffect(
    key1 = Unit,
    effect = {
      onDispose {
        focusRequester.freeFocusSafe()

        // TODO: New reply layout
//                if (!globalUiInfoManager.isAnyReplyLayoutOpened()) {
//                    localSoftwareKeyboardController?.hide()
//                }
      }
    }
  )

  val makeNewThreadHint = stringResource(id = R.string.reply_make_new_thread_hint)
  val replyInThreadHint = stringResource(id = R.string.reply_reply_in_thread_hint)

  val labelText = remember(replyLayoutState.isCatalogMode, replyText.text.length) {
    buildString {
      val commentLabelText = if (replyLayoutState.isCatalogMode) {
        makeNewThreadHint
      } else {
        replyInThreadHint
      }

      append(commentLabelText)

      if (replyText.text.isNotEmpty()) {
        append(" ")
        append("(")

        append(replyText.text.length)

        if (maxCommentLength > 0) {
          append("/")
          append(maxCommentLength)
        }

        append(")")
      }
    }
  }

  val minHeightModifier = if (replyLayoutVisibility == ReplyLayoutVisibility.Expanded) {
    Modifier.heightIn(min = 200.dp)
  } else {
    Modifier
  }

  val mutableInteractionSource = remember { MutableInteractionSource() }

  KurobaComposeTextField(
    modifier = Modifier
        .fillMaxSize()
        .padding(vertical = 4.dp)
        .focusable(interactionSource = mutableInteractionSource)
        .focusRequester(focusRequester)
        .then(minHeightModifier),
    enabled = replyLayoutEnabled,
    value = replyText,
    singleLine = false,
    maxLines = Int.MAX_VALUE,
    visualTransformation = replyInputVisualTransformation,
    keyboardOptions = KeyboardOptions(
      capitalization = KeyboardCapitalization.Sentences
    ),
    label = { interactionSource ->
      KurobaLabelText(
        enabled = replyLayoutEnabled,
        labelText = labelText,
        fontSize = 14.sp,
        interactionSource = interactionSource
      )
    },
    onValueChange = { newTextFieldValue -> replyLayoutState.onReplyTextChanged(newTextFieldValue) },
    interactionSource = mutableInteractionSource
  )
}