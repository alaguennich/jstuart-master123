package fr.obeo.tools.stuart.jenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.squareup.okhttp.Authenticator;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Request.Builder;
import com.squareup.okhttp.Response;

import fr.obeo.tools.stuart.Post;
import fr.obeo.tools.stuart.jenkins.model.BuildAction;
import fr.obeo.tools.stuart.jenkins.model.BuildArtifact;
import fr.obeo.tools.stuart.jenkins.model.BuildAuthor;
import fr.obeo.tools.stuart.jenkins.model.BuildCause;
import fr.obeo.tools.stuart.jenkins.model.BuildResult;
import fr.obeo.tools.stuart.jenkins.model.ChangeSetItem;
import fr.obeo.tools.stuart.jenkins.model.Job;
import fr.obeo.tools.stuart.jenkins.model.ServerResult;
import fr.obeo.tools.stuart.jenkins.model.testResults.ChildReport;
import fr.obeo.tools.stuart.jenkins.model.testResults.TestCase;
import fr.obeo.tools.stuart.jenkins.model.testResults.TestReport;
import fr.obeo.tools.stuart.jenkins.model.testResults.TestSuite;

public class JenkinsLogger {

	private static final int MAX_FAILED_TESTS = 20;

	private static final String JENKINS_ICON = "https://i.imgur.com/OMD63Ns.png";

	private String serverURL;
	private Gson gson;

	private Date daysAgo;

	private String username;

	private String password;

	public JenkinsLogger(String serverURL, Date daysAgo) {
		this.serverURL = serverURL;
		gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").setPrettyPrinting().create();
		this.daysAgo = daysAgo;
	}

	public void setAuth(String username, String password) {
		this.username = username;
		this.password = password;
	}

	public Collection<Post> getBuildResults() {
		return getBuildResults(Sets.<String>newLinkedHashSet(), Predicates.alwaysTrue());
	}

	public Collection<Post> getBuildResults(Set<String> alreadySent) {
		return getBuildResults(alreadySent, Predicates.alwaysTrue());
	}

	public Collection<Post> getBuildResults(Set<String> alreadySent, Predicate<Job> jobFilter) {
		List<Post> posts = new ArrayList<Post>();
		String url = serverURL + "api/json?tree=jobs[name,lastBuild[building,timestamp,url]]";
		OkHttpClient client = new OkHttpClient();

		Builder requestBuilder = new Request.Builder().url(url).get();
		if (username != null && password != null) {
			requestBuilder.header("Authorization", Credentials.basic(username, password));
		}
		Request request = requestBuilder.build();
		Response response = null;
		try {
			response = client.newCall(request).execute();
			String returnedJson = response.body().string();
			ServerResult recentReviews = gson.fromJson(returnedJson, ServerResult.class);
			for (Job j : recentReviews.getJobs()) {
				if (j.getLastBuild() != null && !j.getLastBuild().getUrl().contains("gerrit") && jobFilter.apply(j)) {
					String jURL = j.getLastBuild().getUrl() + "/api/json";

					Date lastJobTime = new Date(j.getLastBuild().getTimestamp());
					if (lastJobTime.after(daysAgo)) {

						requestBuilder = new Request.Builder().url(jURL).get();
						if (username != null && password != null) {
							requestBuilder.header("Authorization", Credentials.basic(username, password));
						}

						Response jobResponse = client.newCall(requestBuilder.build()).execute();
						String jobJson = jobResponse.body().string();
						BuildResult lastBuild = gson.fromJson(jobJson, BuildResult.class);
						String postKey = lastBuild.getUrl();
						if (!alreadySent.contains(postKey) && !lastBuild.isBuilding()) {
							boolean manualTrigger = false;
							int totalCount = 0;
							int failCount = 0;
							int skippedCount = 0;
							boolean hasRecentRegressions = false;
							String testReportURLName = null;

							String testsResults = "";

							for (BuildAction action : lastBuild.getActions()) {
								totalCount += action.getTotalCount();
								failCount += action.getFailCount();
								skippedCount += action.getSkipCount();
								if (totalCount > 0) {
									testReportURLName = action.getUrlName();
									String rootReportURL = j.getLastBuild().getUrl() + testReportURLName;
									Request requestXMLTestReport = requestBuilder
											.url(rootReportURL + "/api/json?pretty=true").get().build();
									TestReport rootReport = gson.fromJson(
											client.newCall(requestXMLTestReport).execute().body().charStream(),
											TestReport.class);

									Map<String, TestReport> reports = allNonEmptyReports(rootReport, rootReportURL);
									StringBuffer reportString = new StringBuffer();

									reportString.append("failed : " + failCount + "/" + totalCount + " (skipped : "
											+ skippedCount + ")\n\n");
									if (failCount > 0) {
										hasRecentRegressions = hasRecentRegressions
												|| generatePerTestCaseReport(reports, reportString);
									}
									if (reports.entrySet().size() > 0 || failCount == 0) {
										testsResults = reportString.toString();
									}
								}
								for (BuildCause cause : action.getCauses()) {
									if (cause.getShortDescription() != null) {
										manualTrigger = cause.getShortDescription().contains("user")
												|| cause.getShortDescription().contains("utilisateur");
									}

								}
							}

							Collection<String> authors = Sets.newLinkedHashSet();
							for (BuildAuthor culprit : lastBuild.getCulprits()) {
								if (culprit.getFullName() != null) {
									authors.add(culprit.getFullName());
								}
							}

							Collection<String> comments = Sets.newLinkedHashSet();
							for (ChangeSetItem i : lastBuild.getChangeSet().getItems()) {
								if (i.getComment() != null) {
									comments.add(
											Iterables.getFirst(Splitter.on('\n').split(i.getComment()), "no commit"));
								}
							}

							String authorTxt = "...";
							if (authors.size() <= 2 && authors.size() > 0) {
								authorTxt = Joiner.on(',').join(authors);
							}
							if (manualTrigger || hasRecentRegressions) {
								String body = "";
								if (hasRecentRegressions || failCount == 0) {
									body += testsResults + "\n";
								} else if (comments.size() > 0) {
									body = Joiner.on('\n').join(comments);
								}
								if (lastBuild.getArtifacts().size() > 0) {
									body += "\nArtifacts:";
									for (BuildArtifact artifact : lastBuild.getArtifacts()) {
										body += "\n* [" + artifact.getDisplayPath() + "](" + lastBuild.getUrl()
												+ "artifact/" + artifact.getRelativePath() + ")";
									}
									body += "\n";
								}
								String statusIcon = "https://i.imgur.com/LfNQATL.png";
								if ("UNSTABLE".equals(lastBuild.getResult())) {
									statusIcon = "https://i.imgur.com/ODJohQh.png";
								} else if ("STABLE".equals(lastBuild.getResult())) {
									statusIcon = "https://i.imgur.com/OgGlGdI.png";
								} else if ("FAILED".equals(lastBuild.getResult())) {
									statusIcon = "https://i.imgur.com/LfNQATL.png";
								} else if ("SUCCESS".equals(lastBuild.getResult())) {
									statusIcon = "https://i.imgur.com/OgGlGdI.png";
								}
								Post newPost = Post.createPostWithSubject(postKey,
										" [![](" + statusIcon + ") " + lastBuild.getFullDisplayName() + "](" + postKey
												+ ")",
										body, authorTxt, JENKINS_ICON, new Date(lastBuild.getTimestamp()));
								newPost.mightBeTruncated(false);
								newPost.setQuote(false);
								posts.add(newPost);
							}
						}
					}
				}

			}

			response.body().close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return posts;
	}

	private Predicate<TestCase> isRecentRegression = new Predicate<TestCase>() {

		@Override
		public boolean apply(TestCase input) {
			return "FAILED".equals(input.getStatus()) || "REGRESSION".equals(input.getStatus()) && input.getAge() < 30;
		}
	};

	private boolean generatePerTestCaseReport(Map<String, TestReport> reports, StringBuffer out) {
		Map<TestReport, String> reportToName = Maps.newLinkedHashMap();
		boolean hasRecentRegressions = false;
		Multimap<String, TestReport> testNameToReports = HashMultimap.create();
		for (Map.Entry<String, TestReport> r : reports.entrySet()) {
			reportToName.put(r.getValue(), r.getKey());
			if (r.getValue().getFailCount() > 0) {
				for (TestSuite suite : r.getValue().getSuites()) {

					List<TestCase> casesToDisplay = Lists
							.newArrayList(Iterables.filter(suite.getCases(), isRecentRegression));
					if (casesToDisplay.size() > 0) {
						hasRecentRegressions = true;
						for (TestCase tCase : casesToDisplay) {
							String testName = getTestName(tCase);
							testNameToReports.put(testName, r.getValue());
						}

					}

				}
			}
		}

		out.append("| Test");
		Collection<TestReport> reportsWithFailures = Sets.newLinkedHashSet(testNameToReports.values());
		for (TestReport r : reportsWithFailures) {
			String reportURL = reportToName.get(r);
			String name = reportURL.substring(reportURL.indexOf("./") + 2);
			if (name.indexOf("PLATFORM=") != -1) {
				name = name.substring(name.indexOf("PLATFORM=") + 9);
			}
			if (name.indexOf(",") != -1) {
				name = name.substring(0, name.indexOf(","));
			}
			if (name.length() > 10) {
				name = "report";
			}

			out.append('|');
			out.append("[" + name + "](" + reportURL + ")");
		}
		out.append("|\n");
		out.append("|----------");
		for (int i = 0; i < reports.entrySet().size(); i++) {
			out.append("|--------");
		}
		out.append("|\n");
		int nb = 0;
		for (String testName : testNameToReports.keySet()) {
			if (nb < 20) {
				Collection<TestReport> configWhereItFails = testNameToReports.get(testName);
				boolean hasRecentFailure = false;
				for (TestReport r : reportsWithFailures) {
					if (configWhereItFails.contains(r)) {
						TestCase tCase = findTestCaseFromName(testName, r);
						if (tCase != null) {
							if (tCase.getAge() <= 10) {
								hasRecentFailure = true;
							}

						}
					}
				}
				if (hasRecentFailure) {
					out.append("|**" + testName + "**");
				} else {
					out.append("|" + testName);
				}

				for (TestReport r : reportsWithFailures) {
					if (configWhereItFails.contains(r)) {
						out.append("|");
						TestCase tCase = findTestCaseFromName(testName, r);
						if (tCase != null) {
							if (hasRecentFailure) {
								out.append("**");
							}
							out.append(tCase.getAge());
							if (hasRecentFailure) {
								out.append("**");
							}
						} else {
							out.append('X');
						}
					} else {
						out.append("| ");
					}
				}
				out.append("|\n");
			}
			nb++;
		}
		if (nb > MAX_FAILED_TESTS) {
			out.append(":warning: **" + nb + "**  recent failed tests in total, some could not be displayed.");
		}
		return hasRecentRegressions;
	}

	private TestCase findTestCaseFromName(String testName, TestReport r) {
		TestCase found = null;
		Iterator<TestSuite> it = r.getSuites().iterator();
		while (found == null && it.hasNext()) {
			TestSuite s = it.next();
			Iterator<TestCase> itC = s.getCases().iterator();
			while (found == null && itC.hasNext()) {
				TestCase c = itC.next();
				if (testName.equals(getTestName(c))) {
					found = c;
				}

			}
		}
		return found;
	}

	private String getTestName(TestCase tCase) {
		String className = tCase.getClassName();
		if (className.lastIndexOf(".") != -1) {
			className = className.substring(className.lastIndexOf(".") + 1);
		}
		String testName = className + "." + tCase.getName();
		return testName;
	}

	private boolean generatePerConfigurationReport(Map<String, TestReport> reports, StringBuffer out) {
		boolean hasRecentRegressions = false;
		out.append("| Configuration        | Duration  | All  | Failed | Skipped | Age |\n");
		out.append("| -------------------- |:---------:| ----:|-------:|--------:|-----|\n");
		for (Map.Entry<String, TestReport> r : reports.entrySet()) {
			if (r.getValue().getFailCount() > 0) {
				out.append("| " + r.getKey() + "         | " + Math.round(r.getValue().getDuration() / 60) + " min | "
						+ r.getValue().getTotalCount() + " |" + r.getValue().getFailCount() + " | "
						+ r.getValue().getSkipCount() + "|   |\n");
				for (TestSuite suite : r.getValue().getSuites()) {
					List<TestCase> casesToDisplay = Lists
							.newArrayList(Iterables.filter(suite.getCases(), isRecentRegression));
					if (casesToDisplay.size() > 0) {
						hasRecentRegressions = true;
						for (TestCase tCase : casesToDisplay) {
							String testName = getTestName(tCase);
							if (tCase.getAge() <= 2) {
								out.append("|**" + testName + "**|" + Math.round(tCase.getDuration()) + " sec | " + " "
										+ "|" + " " + "|" + " " + "|**" + tCase.getAge() + "**|\n");
							} else {
								out.append("|" + testName + "| " + Math.round(tCase.getDuration()) + " sec|" + " " + "|"
										+ " " + "|" + " " + "|" + tCase.getAge() + "|\n");
							}

						}

					}

				}
			}
		}
		return hasRecentRegressions;
	}

	public static Map<String, TestReport> allNonEmptyReports(TestReport rep, String rootURL) {
		Map<String, TestReport> result = Maps.newLinkedHashMap();
		if (rep.getChildReports().size() > 0) {
			for (ChildReport childReport : rep.getChildReports()) {
				TestReport childTestResults = childReport.getResult();
				if (childTestResults.getSkipCount() > 0 || childTestResults.getPassCount() > 0) {
					String name = childReport.getChild().getUrl()
							.substring(childReport.getChild().getUrl().indexOf("./") + 2);
					result.put(childReport.getChild().getUrl(), childTestResults);
				}
			}
		} else if (rep.getTotalCount() > 0) {
			result.put(rootURL, rep);
		}
		return result;
	}

}
