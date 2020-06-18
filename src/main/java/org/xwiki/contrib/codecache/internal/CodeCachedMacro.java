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
 * Create a link to a section id, displaying the section number as the link label.
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

    @Override
    public boolean supportsInlineMode()
    {
        return true;
    }

    @Override
    public List<Block> execute(CodeMacroParameters parameters, String content, MacroTransformationContext context)
        throws MacroExecutionException
    {
        CodeCacheBlockAsyncRenderer renderer;
        try {
            renderer = this.contextComponentManager.getInstance(CodeCacheBlockAsyncRenderer.class);
        } catch (ComponentLookupException e) {
            throw new MacroExecutionException("Failed to create code cache async renderer", e);
        }
        renderer.initialize(this, parameters, content, context);

        AsyncRendererConfiguration configuration = new AsyncRendererConfiguration();

        // Execute the renderer
        Block result;
        try {
            result = this.executor.execute(renderer, configuration);
        } catch (Exception e) {
            throw new MacroExecutionException("Failed to execute code cache macro", e);
        }

        return result instanceof CompositeBlock ? result.getChildren() : Arrays.asList(result);
    }

    @Override
    public List<Block> parseContent(CodeMacroParameters parameters, String content,
        MacroTransformationContext context) throws MacroExecutionException
    {
        return super.parseContent(parameters, content, context);
    }
}
