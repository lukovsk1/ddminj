#!/bin/bash

# update repo
git pull

ALGORITHMS=()
BUGS=()

echo "Select algorithms (anything else to continue)"
select algorithm in HDD GDD HDDrec GDDrec CodeLine; do
  if [ -z "$algorithm" ]; then
    echo "breaking"
    break
  fi
  ALGORITHMS+=($algorithm)
  echo "You have chosen ${ALGORITHMS[@]}"
done

echo "Select bugs (anything else to continue)"
select bug in csv_4 gson_6 cli_1 lang_5 jsoup_9; do
  if [ -z "$bug" ]; then
    echo "breaking"
    break
  fi
  BUGS+=($bug)
  echo "You have chosen ${BUGS[@]}"
done

# build the jar
java -jar target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar

for bug in $BUGS; do
  for algorithm in $ALGORITHMS; do
    echo "Running algorithm $algorithm for bug $bug"
    if [[ $bug == csv_4 ]]; then
      java -jar ~/workspace/ddminj/target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar "generator.$(algorithm)MWEGenerator" ~/workspace/defects4j/bugs/csv_4_b/ src/main/java src/test/java org.apache.commons.csv.CSVParserTest#testNoHeaderMap "java.lang.NullPointerException"
    elif [[ $bug == gson_6 ]]; then
      java -jar ~/workspace/ddminj/target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar "generator.$(algorithm)MWEGenerator" ~/workspace/defects4j/bugs/gson_6_b/ gson/src/main/java/ gson/src/test/java/ com.google.gson.regression.JsonAdapterNullSafeTest#testNullSafeBugSerialize java.lang.NullPointerException
    elif [[ $bug == cli_1 ]]; then
      java -jar ~/workspace/ddminj/target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar "generator.$(algorithm)MWEGenerator" ~/workspace/defects4j/bugs/cli_1_b/ src/java/ src/test/ org.apache.commons.cli.bug.BugCLI13Test#testCLI13 junit.framework.AssertionFailedError
    elif [[ $bug == lang_5 ]]; then
      java -jar ~/workspace/ddminj/target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar "generator.$(algorithm)MWEGenerator" ~/workspace/defects4j/bugs/lang_5_b/ src/main/java/ src/test/java/ org.apache.commons.lang3.LocaleUtilsTest#testLang865 "java.lang.IllegalArgumentException: Invalid locale format: _GB"
    elif [[ $bug == jsoup_9 ]]; then
      java -jar ~/workspace/ddminj/target/ddminj-1.0-SNAPSHOT-jar-with-dependencies.jar "generator.$(algorithm)MWEGenerator" ~/workspace/defects4j/bugs/jsoup_9_b/ src/main/java/ src/test/java/ org.jsoup.nodes.EntitiesTest#unescape org.junit.ComparisonFailure
    fi
  done
done

# check in logs and stats
git add ./stats
git add ./logs
git commit -m "VM run"
git push

