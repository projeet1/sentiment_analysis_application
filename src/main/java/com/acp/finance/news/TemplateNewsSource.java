package com.acp.finance.news;

import com.acp.finance.model.NewsArticle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
@ConditionalOnProperty(
    name = "NEWS_SOURCE",
    havingValue = "template",
    matchIfMissing = true)
public class TemplateNewsSource implements NewsSource {

    private static final Logger log = LoggerFactory.getLogger(TemplateNewsSource.class);

    private final Random random = new Random();

    @Value("${AAPL_BIAS:0.8}")
    private double aaplBias;

    @Value("${TSLA_BIAS:0.2}")
    private double tslaBias;

    @Value("${MSFT_BIAS:0.65}")
    private double msftBias;

    @Value("${GOOGL_BIAS:0.5}")
    private double googlBias;

    private static final Map<String, String> COMPANY_NAMES = Map.of(
            "AAPL", "Apple",
            "TSLA", "Tesla",
            "MSFT", "Microsoft",
            "GOOGL", "Alphabet"
    );

    private static final String[] POSITIVE = {
        "{company} beats Q{n} earnings, raises full-year guidance",
        "{company} shares surge after record quarterly revenue",
        "{company} announces dividend increase of {n}%",
        "{company} profit jumps on strong {division} demand",
        "{company} gains market share as revenue growth accelerates",
        "{company} reports record profit driven by {division} unit",
        "{company} stock rises after exceeding analyst expectations",
        "{company} announces {n} billion share buyback programme",
        "{company} upgrades full-year outlook on strong demand",
        "{company} expands into new markets driving revenue growth",
        "{company} secures major enterprise contract worth {n} billion",
        "{company} AI division drives record quarterly profit growth",
        "{company} reports strongest earnings in five years",
        "{company} raises dividend as cash flow hits record high",
        "{company} shares jump after surprise earnings beat"
    };

    private static final String[] NEGATIVE = {
        "{company} misses revenue estimates, shares fall in trading",
        "{company} cuts full-year outlook amid weak consumer demand",
        "{company} faces regulatory scrutiny over {division} practices",
        "{company} reports unexpected loss in Q{n} results",
        "{company} shares decline after analyst downgrades to sell",
        "{company} announces layoffs affecting {n} thousand employees",
        "{company} warns of slowing growth as competition intensifies",
        "{company} hit with {n} billion antitrust lawsuit in EU",
        "{company} misses profit targets as costs rise sharply",
        "{company} shares drop after CFO resignation announced",
        "{company} faces supply chain disruption hitting Q{n} margins",
        "{company} revenue falls short as {division} unit disappoints",
        "{company} cuts dividend amid deteriorating financial outlook",
        "{company} stock falls on weak forward guidance",
        "{company} reports margin compression in core {division} business"
    };

    private static final String[] NEUTRAL = {
        "{company} announces strategic review of {division} unit",
        "{company} appoints new CFO effective next quarter",
        "{company} to present at upcoming investor conference",
        "{company} updates corporate governance policy",
        "{company} files quarterly report with SEC",
        "{company} announces board of directors reshuffle",
        "{company} to host annual general meeting next month",
        "{company} releases updated sustainability report",
        "{company} confirms no change to current dividend policy",
        "{company} schedules earnings call for end of quarter"
    };

    private static final String[] DIVISIONS = {
        "cloud", "AI", "advertising", "hardware", "software",
        "services", "retail", "enterprise", "mobile", "streaming"
    };

    @Override
    public List<NewsArticle> getLatestHeadlines(String ticker, int count) {
        double bias = getBias(ticker);
        String company = COMPANY_NAMES.getOrDefault(ticker, ticker);
        List<NewsArticle> articles = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            double roll = random.nextDouble();
            String template;
            if (roll < 0.1) {
                template = NEUTRAL[random.nextInt(NEUTRAL.length)];
            } else if (roll < bias) {
                template = POSITIVE[random.nextInt(POSITIVE.length)];
            } else {
                template = NEGATIVE[random.nextInt(NEGATIVE.length)];
            }

            String title = fillTemplate(template, company);

            NewsArticle article = new NewsArticle();
            article.setTicker(ticker);
            article.setTitle(title);
            article.setDescription("");
            article.setPublishedAt(Instant.now().toString());
            article.setSource("TemplateNews");
            articles.add(article);

            log.info("[TemplateNews] {} -> {}", ticker, title);
        }

        return articles;
    }

    private double getBias(String ticker) {
        return switch (ticker) {
            case "AAPL" -> aaplBias;
            case "TSLA" -> tslaBias;
            case "MSFT" -> msftBias;
            case "GOOGL" -> googlBias;
            default -> 0.5;
        };
    }

    private String fillTemplate(String template, String company) {
        String result = template.replace("{company}", company);
        result = result.replace("{division}", DIVISIONS[random.nextInt(DIVISIONS.length)]);
        // Context-aware {n} substitution — order matters
        result = result.replaceFirst("Q\\{n\\}", "Q" + (random.nextInt(4) + 1));
        result = result.replaceFirst("\\{n\\} billion", (random.nextInt(50) + 1) + " billion");
        result = result.replaceFirst("\\{n\\} thousand", (random.nextInt(100) + 1) + " thousand");
        result = result.replaceFirst("\\{n\\}%", (random.nextInt(21) + 5) + "%");
        // Catch-all for any remaining {n}
        result = result.replace("{n}", String.valueOf(random.nextInt(4) + 1));
        return result;
    }
}
