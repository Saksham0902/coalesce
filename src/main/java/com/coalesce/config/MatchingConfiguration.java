package com.coalesce.config;

import com.coalesce.domain.match.AttributeComparator;
import com.coalesce.domain.match.ComparatorRegistry;
import com.coalesce.domain.match.comparator.DateComparator;
import com.coalesce.domain.match.comparator.ExactComparator;
import com.coalesce.domain.match.comparator.JaroWinklerComparator;
import com.coalesce.domain.match.comparator.LevenshteinComparator;
import com.coalesce.domain.match.comparator.NumericComparator;
import com.coalesce.domain.match.comparator.SoundexComparator;
import com.coalesce.domain.match.comparator.TokenCosineComparator;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the comparators into a registry.
 *
 * <p>The comparators themselves carry no Spring annotations. That is deliberate: the {@code domain}
 * package has no framework dependency at all, so every algorithm in it can be unit tested by calling a
 * constructor, with no application context and no startup cost. Composition is the framework's job and
 * it happens here, at the edge.
 *
 * <p>Spring injects every {@link AttributeComparator} bean it can find, so adding a comparator means
 * adding a class and one {@code @Bean} method — no existing file changes.
 */
@Configuration
public class MatchingConfiguration {

    @Bean
    AttributeComparator exactComparator() {
        return new ExactComparator();
    }

    @Bean
    AttributeComparator jaroWinklerComparator() {
        return new JaroWinklerComparator();
    }

    @Bean
    AttributeComparator levenshteinComparator() {
        return new LevenshteinComparator();
    }

    @Bean
    AttributeComparator soundexComparator() {
        return new SoundexComparator();
    }

    @Bean
    AttributeComparator tokenCosineComparator() {
        return new TokenCosineComparator();
    }

    @Bean
    AttributeComparator numericComparator() {
        return new NumericComparator();
    }

    @Bean
    AttributeComparator dateComparator() {
        return new DateComparator();
    }

    @Bean
    ComparatorRegistry comparatorRegistry(List<AttributeComparator> comparators) {
        return new ComparatorRegistry(comparators);
    }
}
