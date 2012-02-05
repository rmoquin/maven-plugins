package com.goldin.plugins.ivy

import com.goldin.gcommons.GCommons
import org.apache.ivy.Ivy
import org.apache.ivy.core.module.descriptor.DefaultDependencyDescriptor
import org.apache.ivy.core.module.descriptor.DefaultIncludeRule
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor
import org.apache.ivy.core.module.id.ArtifactId
import org.apache.ivy.core.module.id.ModuleRevisionId
import org.apache.ivy.core.report.ResolveReport
import org.apache.ivy.core.resolve.ResolveOptions
import org.apache.ivy.core.settings.IvySettings
import org.apache.ivy.plugins.matcher.ExactPatternMatcher
import org.apache.ivy.plugins.parser.xml.XmlModuleDescriptorWriter
import org.gcontracts.annotations.Requires
import org.sonatype.aether.RepositorySystemSession
import org.sonatype.aether.impl.ArtifactResolver
import org.sonatype.aether.resolution.ArtifactRequest
import org.sonatype.aether.resolution.ArtifactResult
import org.sonatype.aether.util.artifact.DefaultArtifact

/**
 *
 */
class IvyArtifactResolver implements ArtifactResolver {

    private final ArtifactResolver delegate
    private final Ivy              ivy


    @Requires({ delegate && ivyconf?.file })
    IvyArtifactResolver ( ArtifactResolver delegate, File ivyconf )
    {
        this.delegate = delegate
        this.ivy      = buildIvy( ivyconf )
    }


    private Ivy buildIvy ( File ivyconf )
    {
        IvySettings settings = new IvySettings()
        settings.load( ivyconf )
        Ivy.newInstance( settings )
    }


    @Requires({ request && request.artifact })
    ArtifactResult resolveIvy( ArtifactRequest request )
    {
        if ( ! request.artifact.groupId.startsWith( 'ivy:' )) { return null }

        final a            = request.artifact
        final organisation = a.groupId.substring( 'ivy:'.size())
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

            ResolveOptions options = new ResolveOptions()
            options.confs          = [ 'default' ]
            ResolveReport report   = ivy.resolve( ivyfile.toURL(), options )

            assert report.artifacts, \
                "No artifacts resolved for \"$organisation:$name:$revision\"."

            assert report.artifacts.size() == 1, \
                "Multiple artifacts resolved for \"$organisation:$name:$revision\" - [${ report.artifacts }], specify <classifier> pattern."

            final  f = report.allArtifactsReports.first().localFile
            assert f.file, "File [$f] resolved by Ivy isn't found"

            final result    = new ArtifactResult( request )
            result.artifact = new DefaultArtifact( organisation, name, extension, revision ).setFile( f )
            result
        }
        finally
        {
            GCommons.file().delete( ivyfile )
        }
    }


    @Requires({ requests != null })
    List<ArtifactResult> resolveIvy( Collection<? extends ArtifactRequest> requests )
    {
        requests.collect { resolveIvy( it ) }
    }


    @Override
    ArtifactResult resolveArtifact ( RepositorySystemSession session, ArtifactRequest request )
    {
        resolveIvy( request ) ?: delegate.resolveArtifact(  session, request )
    }


    @Override
    List<ArtifactResult> resolveArtifacts ( RepositorySystemSession session, Collection<? extends ArtifactRequest> requests )
    {
        final result = resolveIvy( requests )
        result.any() ? result : delegate.resolveArtifacts( session, requests )
    }
}