// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyReference;

public interface GrUnaryExpression extends GrExpression {

  @NotNull
  @Override
  GroovyReference getReference();

  /**
   * @return type of this expression with regard to whether this expression is prefix or postfix
   */
  @Nullable
  @Override
  PsiType getType();

  /**
   * @return type of operator call performed by this expression independently of whether this expression is prefix or postfix
   */
  @Nullable
  PsiType getOperationType();

  @NotNull
  IElementType getOperationTokenType();

  @NotNull
  PsiElement getOperationToken();

  @Nullable
  GrExpression getOperand();

  boolean isPostfix();
}
