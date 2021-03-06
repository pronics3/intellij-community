// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInsight.editorActions;

import com.intellij.codeInsight.highlighting.BraceHighlightingHandler;
import com.intellij.codeInsight.highlighting.BraceMatcher;
import com.intellij.codeInsight.highlighting.BraceMatchingUtil;
import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Caret;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilBase;
import org.jetbrains.annotations.NotNull;

/**
 * Moves caret to the the matching brace:
 * - If caret is on the closing brace - moves to the matching opening
 * - If caret is on the opening brace - moves to the matching closing brace
 * - Otherwise moves from the caret position to the beginning of the file and finds first opening brace not closed before the caret position
 */
public class MatchBraceAction extends EditorAction {
  public MatchBraceAction() {
    super(new MyHandler());
  }

  private static class MyHandler extends EditorActionHandler {
    MyHandler() {
      super(true);
    }

    @Override
    public void execute(@NotNull Editor editor, DataContext dataContext) {
      final PsiFile file = CommonDataKeys.PSI_FILE.getData(dataContext);
      if (file == null) return;


      int targetOffset = getClosestTargetOffset(editor, file);

      if (targetOffset > -1) {
        moveCaret(editor, editor.getCaretModel().getCurrentCaret(), targetOffset);
      }
    }

    /**
     * Attempts to find closest target offset for the caret. Uses {@link BraceMatcher} and {@link CodeBlockSupportHandler}
     *
     * @return target caret offset or -1 if uncomputable.
     */
    private static int getClosestTargetOffset(@NotNull Editor editor, @NotNull PsiFile file) {
      int offsetFromBraceMatcher = getOffsetFromBraceMatcher(editor, file);
      TextRange rangeFromCodeBlockSupport = CodeBlockSupportHandler.findCodeBlockRange(editor, file);
      if (rangeFromCodeBlockSupport.isEmpty() || rangeFromCodeBlockSupport.contains(offsetFromBraceMatcher)) {
        return offsetFromBraceMatcher;
      }

      final EditorHighlighter highlighter = ((EditorEx)editor).getHighlighter();
      int caretOffset = editor.getCaretModel().getOffset();
      HighlighterIterator iterator = highlighter.createIterator(caretOffset);

      // end of file or at block closing token
      if (iterator.atEnd() || iterator.getEnd() == rangeFromCodeBlockSupport.getEndOffset() ||
          // edge case - end of closing token
          (caretOffset > 0 && highlighter.createIterator(caretOffset - 1).getEnd() == rangeFromCodeBlockSupport.getEndOffset())) {
        return rangeFromCodeBlockSupport.getStartOffset();
      }
      return rangeFromCodeBlockSupport.getEndOffset();
    }

    /**
     * @return offset to move caret to, computed from the brace matcher. If it's not possible to compute - returns {@code -1}
     * @implNote this code partially duplicates {@link BraceHighlightingHandler#updateBraces()} and probably can be extracted.
     */
    private static int getOffsetFromBraceMatcher(@NotNull Editor editor, @NotNull PsiFile file) {
      final Caret caret = editor.getCaretModel().getCurrentCaret();
      final EditorHighlighter highlighter = BraceHighlightingHandler.getLazyParsableHighlighterIfAny(file.getProject(), editor, file);
      final CharSequence text = editor.getDocument().getCharsSequence();

      int offset = caret.getOffset();
      FileType fileType = getFileType(file, offset);
      HighlighterIterator iterator = highlighter.createIterator(offset);

      if (iterator.atEnd()) {
        // end of file, caret should be in the beginning of the brace
        offset--;
      }
      else if (offset > 0 &&
               !BraceMatchingUtil.isLBraceToken(iterator, text, fileType) &&
               !BraceMatchingUtil.isRBraceToken(iterator, text, fileType)) {
        // we probably standing after a brace, let's look behind one char
        offset--;

        HighlighterIterator i = highlighter.createIterator(offset);
        if (!BraceMatchingUtil.isRBraceToken(i, text, getFileType(file, i.getStart())) &&
            !BraceMatchingUtil.isLBraceToken(i, text, getFileType(file, i.getStart()))) {
          // we still not at brace
          offset++;
        }
      }

      if (offset < 0) return -1;

      iterator = highlighter.createIterator(offset);
      fileType = getFileType(file, iterator.getStart());

      boolean isLeftBrace = BraceMatchingUtil.isLBraceToken(iterator, text, fileType);
      if (isLeftBrace || BraceMatchingUtil.isRBraceToken(iterator, text, fileType)) {
        // we are at brace
        if (BraceMatchingUtil.matchBrace(text, fileType, iterator, isLeftBrace)) {
          return iterator.getStart();
        }
        return -1;
      }

      int unopenedBraces = 0;
      while (true) {
        if (BraceMatchingUtil.isRBraceToken(iterator, text, fileType)) {
          unopenedBraces++;
        }
        else if (BraceMatchingUtil.isLBraceToken(iterator, text, fileType)) {
          unopenedBraces--;
        }
        if (unopenedBraces < 0) {
          return iterator.getStart();
        }

        if (iterator.getStart() == 0) {
          return -1;
        }
        iterator.retreat();
      }
    }
  }

  @NotNull
  private static FileType getFileType(PsiFile file, int offset) {
    return PsiUtilBase.getPsiFileAtOffset(file, offset).getFileType();
  }

  private static void moveCaret(Editor editor, Caret caret, int offset) {
    caret.removeSelection();
    caret.moveToOffset(offset);
    EditorModificationUtil.scrollToCaret(editor);
  }
}
