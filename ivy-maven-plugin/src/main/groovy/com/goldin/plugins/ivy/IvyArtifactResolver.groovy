package com.goldin.plugins.ivy

import com.goldin.gcommons.GCommons
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor
import org.apache.ivy.core.module.descriptor.DefaultIncludeRule
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor
import org.apache.ivy.core.module.id.ArtifactId
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.plugins.matcher.ExactPatternMatcher
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter
import org.gcontracts.annotations.Ensures
import org.gcontracts.annotations.Requires
import org.sonatype.aether.RepositorySystemSession
import org.sonatype.aether.impl.ArtifactResolver
import org.sonatype.aether.resolution.ArtifactRequest
import org.sonatype.aether.resolution.ArtifactResult
import org.sonatype.aether.util.artifact.DefaultArtifact


/**
 * Resolved Ivy artifacts using the settings file specified.
 */
class IvyArtifactResolver implements ArtifactResolver {

    private final ArtifactResolver delegateResolver
    private final Ivy              ivy
    private final IvyHelper        ivyHelper


    @Requires({ delegateResolver && ivy && ivyHelper })
    IvyArtifactResolver ( ArtifactResolver delegateResolver, Ivy ivy, IvyHelper ivyHelper )
    {
        this.delegateResolver = delegateResolver
        this.ivy              = ivy
        this.ivyHelper        = ivyHelper
    }


    /**
     * Attempts to resolve the request using Ivy.
     *
     * @param request artifact request
     * @return artifact resolved if artifact's {@code <groupId>} starts with {@code "ivy:"}, null otherwise.
     */
    @Requires({ request && request.artifact })
    @Ensures({ result && result.artifact.file.file })
    private ArtifactResult resolveIvy( ArtifactRequest request )
    {
        if ( ! request.artifact.groupId.startsWith( IvyMojo.IVY_PREFIX )) { return null }

        final a            = request.artifact
        final organisation = a.groupId.substring( IvyMojo.IVY_PREFIX.size())
        final name         = a.artifactId
        final pattern      = a.classifier
        final revision     = a.version
        final extension    = a.extension

        File ivyfile = File.createTempFile( 'ivy', '.xml' )
        ivyfile.deleteOnExit();

        try
        {
            final md      = DefaultModuleDescriptor.newDefaultInstance( ModuleRevisionId.newInstance( organisation, name + '-caller', 'working' ))
            final module  = ModuleRevisionId.newInstance(organisation, name, revision)
            final dd      = new DefaultDependencyDescriptor( md, module, false, false, true )

            if ( pattern )
            {
                dd.addIncludeRule( '', new DefaultIncludeRule( new ArtifactId( module.moduleId, pattern, extension, extension ),
                                                               new ExactPatternMatcher(), [:] ))
            }

            md.addDependency( dd );
            XmlModuleDescriptorWriter.write( md, ivyfile );

            final artifacts = ivyHelper.resolve( ivy, ivyfile.toURL())

            assert artifacts, \
                "No artifacts resolved for \"$organisation:$name:$revision\"."

            assert artifacts.size() == 1, \
                "Multiple artifacts resolved for \"$organisation:$name:$revision\" - [${ artifacts }], specify <classifier> pattern."

            final  f = artifacts.first().file
            assert f.file, "File [$f] resolved by Ivy isn't found"

            final result    = new ArtifactResult( request )                                               // org.sonatype.aether.resolution.ArtifactResult
            result.artifact = new DefaultArtifact( organisation, name, extension, revision ).setFile( f ) // org.sonatype.aether.util.artifact.DefaultArtifact
            result
        }
        finally
        {
            GCommons.file().delete( ivyfile )
        }
    }


    @Override
    @Requires({ session && request })
    @Ensures({ result })
    ArtifactResult resolveArtifact ( RepositorySystemSession session, ArtifactRequest request )
    {
        resolveIvy( request ) ?: delegateResolver.resolveArtifact( session, request )
    }


    @Override
    List<ArtifactResult> resolveArtifacts ( RepositorySystemSession session, Collection<? extends ArtifactRequest> requests )
    {
        requests.collect { resolveArtifact( session, it )}
    }
}