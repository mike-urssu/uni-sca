package sca

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import sca.acquire.Acquirer
import sca.enrich.Enricher
import sca.job.JobStore
import sca.job.Pipeline
import sca.job.ScanStep
import sca.job.TrivyDb
import sca.merge.MergeRules
import sca.product.CopyrightStore
import sca.product.ProductStore
import sca.scan.DockerRunner

@Configuration
class Config {
    @Bean fun jobStore(p: ScaProperties) = JobStore(p.dataDir)
    @Bean fun productStore(p: ScaProperties) = ProductStore(p.dataDir)
    @Bean fun copyrightStore(p: ScaProperties) = CopyrightStore(p.dataDir)
    @Bean fun trivyDb(p: ScaProperties) = TrivyDb(p)
    @Bean fun pipeline(p: ScaProperties) = Pipeline(
        p, Acquirer(),
        ScanStep { spec, log -> DockerRunner().run(spec, p.limits, p.toolTimeout, log) },
        Enricher(p.depsDevBase, p.clearlyDefinedBase, p.dataDir.resolve("cache")),
        MergeRules.load(),
    )
}
