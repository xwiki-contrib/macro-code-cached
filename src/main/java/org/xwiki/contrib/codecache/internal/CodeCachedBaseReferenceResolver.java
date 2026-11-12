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

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.DocumentReferenceResolver;
import org.xwiki.model.reference.EntityReferenceSerializer;
import org.xwiki.rendering.block.Block;
import org.xwiki.rendering.block.MacroBlock;
import org.xwiki.rendering.block.MetaDataBlock;
import org.xwiki.rendering.block.match.MetadataBlockMatcher;
import org.xwiki.rendering.listener.MetaData;
import org.xwiki.rendering.transformation.MacroTransformationContext;

/**
 * Computes the reference of the document that the references found in a macro result (links, images, attachments)
 * are relative to.
 *
 * @version $Id$
 * @since 1.1
 */
@Component(roles = CodeCachedBaseReferenceResolver.class)
@Singleton
public class CodeCachedBaseReferenceResolver
{
    @Inject
    @Named("current")
    private DocumentReferenceResolver<String> currentDocumentReferenceResolver;

    @Inject
    private EntityReferenceSerializer<String> serializer;

    /**
     * @param macroContext the context of the macro being executed
     * @return the absolute reference of the document that the references found in the macro result are relative to,
     *     to be used as {@link MetaData#BASE}
     */
    public String resolve(MacroTransformationContext macroContext)
    {
        // Resolve the references the same way the renderer does when the macro result is part of the document: from
        // the current document, then from the base references found around the macro (e.g. an included document).
        DocumentReference reference = this.currentDocumentReferenceResolver.resolve("");
        MacroBlock macroBlock = macroContext.getCurrentMacroBlock();
        if (macroBlock != null) {
            List<MetaDataBlock> baseBlocks =
                macroBlock.getBlocks(new MetadataBlockMatcher(MetaData.BASE), Block.Axes.ANCESTOR);
            // The ancestors are listed from the closest one, while the base references are relative to the outer ones.
            Collections.reverse(baseBlocks);
            for (MetaDataBlock baseBlock : baseBlocks) {
                reference = this.currentDocumentReferenceResolver
                    .resolve((String) baseBlock.getMetaData().getMetaData(MetaData.BASE), reference);
            }
        }
        return this.serializer.serialize(reference);
    }
}
