/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.contrib.codecache.internal;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.rendering.async.internal.AsyncRendererConfiguration;
import org.xwiki.rendering.async.internal.block.BlockAsyncRendererExecutor;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.CompositeBlock;
import org.xwiki.rendering.internal.macro.code.CodeMacro;
import org.xwiki.rendering.macro.MacroExecutionException;
import org.xwiki.rendering.macro.code.CodeMacroParameters;
import org.xwiki.rendering.transformation.MacroTransformationContext;

/**
 * Replaces the standard code macro to cache its result and, optionally, to compute it asynchronously.
 *
 * @version $Id$
 * @since 1.0
 */
@Component
@Named("code")
@Singleton
public class CodeCachedMacro extends CodeMacro
{
    @Inject
    @Named("context")
    private ComponentManager contextComponentManager;

    @Inject
    private BlockAsyncRendererExecutor executor;

    @Inject
    private CodeCachedConfiguration macroConfiguration;

    @Override
    public boolean supportsInlineMode()
    {
        return true;
    }

    @Override
    public List<Block> execute(CodeMacroParameters parameters, String content, MacroTransformationContext context)
        throws MacroExecutionException
    {
        CodeCachedBlockAsyncRenderer renderer;
        try {
            renderer = this.contextComponentManager.getInstance(CodeCachedBlockAsyncRenderer.class);
        } catch (ComponentLookupException e) {
            throw new MacroExecutionException("Failed to create code cache async renderer", e);
        }
        renderer.initialize(this, parameters, content, context, this.macroConfiguration.isAsync());

        AsyncRendererConfiguration configuration = new AsyncRendererConfiguration();

        // Execute the renderer
        Block result;
        try {
            result = this.executor.execute(renderer, configuration);
        } catch (Exception e) {
            throw new MacroExecutionException("Failed to execute code cache macro", e);
        }

        // The cached blocks are shared by all the code macros with the same content and parameters, while the macro
        // transformation modifies the blocks it receives (e.g. to execute the macros found in the title), so return a
        // copy of them.
        Block copy = result.clone();

        return copy instanceof CompositeBlock ? copy.getChildren() : Arrays.asList(copy);
    }

    List<Block> executeCodeMacro(CodeMacroParameters parameters, String content,
        MacroTransformationContext context) throws MacroExecutionException
    {
        return super.execute(parameters, content, context);
    }
}
