# *** Admin Assets ***

# Get and compile the admin UI SPA from the GitHub repo.

lrs-admin-ui:
	git clone https://github.com/yetanalytics/lrs-admin-ui.git
	cd lrs-admin-ui; git checkout cf6ff4edddf5ba3560869ca198930312a5cfc7ad

lrs-admin-ui/target/bundle: lrs-admin-ui
	cd lrs-admin-ui; make bundle

resources/public/admin: lrs-admin-ui/target/bundle
	mkdir -p resources/public
	cp -r lrs-admin-ui/target/bundle resources/public/admin

# *** Development ***

# `clean-dev` removes all development files.
# `test-h2`, `test-sqlite`, `test-postgres` run tests on in-mem DB instances.
# `ci` runs all tests and is called with every push to GitHub.
# `bench` runs a query benchmarking session on a lrsql instance.

# All other phony targets run lrsql instances that can be used and tested
# during development. All start up with fixed DB properties and seed creds.

.phony: clean-dev, ci, ephemeral, persistent, sqlite, postgres, bench

clean-dev:
	rm -rf *.db *.log resources/public tmp

test-h2:
	clojure -M:test -m lrsql.test-runner --database h2

test-sqlite:
	clojure -M:test -m lrsql.test-runner --database sqlite

test-postgres:
	clojure -M:test -m lrsql.test-runner --database postgres

# TODO: Remove when we migrate to Github Actions
ci:
	clojure -M:test -m lrsql.test-runner --database h2

ephemeral: resources/public/admin
	clojure -X:db-h2 lrsql.h2.main/run-test-h2 :persistent? false

persistent: resources/public/admin
	clojure -X:db-h2 lrsql.h2.main/run-test-h2 :persistent? true

sqlite: resources/public/admin
	clojure -X:db-sqlite lrsql.sqlite.main/run-test-sqlite

# NOTE: Requires a running Postgres instance where:
# - user is lrsql_user
# - password is swordfish
# - db name is lrsql_pg
# - schema is lrsql
postgres: resources/public/admin
	clojure -X:db-postgres lrsql.postgres.main/run-test-postgres

# NOTE: Requires a running lrsql instance!
bench:
	clojure -M:bench -m lrsql.bench http://localhost:8080/xapi/statements \
		-i dev-resources/default/insert_input.json \
		-q dev-resources/default/query_input.json \
		-u username -p password

# *** Build ***

# `clean` removes all artifacts constructed during the build process.
# `bundle` creates a `target/bundle` directory that contains the entire
# lrsql package, including config, docs, JARs, admin UI files, JREs, and
# Windows executables.

.phony: clean, bundle

clean:
	rm -rf target resources/public

# Compile and make Uberjar

target/bundle/lrsql.jar: resources/public/admin
	clojure -X:build uber

# Copy build scripts

target/bundle/bin:
	mkdir -p target/bundle
	cp -r bin target/bundle/bin
	chmod +x target/bundle/bin/*.sh

# Create HTML docs

target/bundle/doc:
	clojure -M:doc -m lrsql.render-doc doc target/bundle/doc

# Copy config

target/bundle/config/lrsql.json.example:
	mkdir -p target/bundle/config
	cp resources/lrsql/config/lrsql.json.example target/bundle/config/lrsql.json.example

target/bundle/config/authority.json.template.example:
	mkdir -p target/bundle/config
	cp resources/lrsql/config/authority.json.template target/bundle/config/authority.json.template.example

target/bundle/config: target/bundle/config/lrsql.json.example target/bundle/config/authority.json.template.example

# Make Runtime Environment (i.e. JREs)

# Download the 3 runtimes from an AWS S3 bucket.

# The given tag to pull down
RUNTIME_TAG ?= 0.1.0-java-11-temurin
RUNTIME_MACHINE ?= macos
RUNTIME_MACHINE_BUILD ?= macOS-latest
RUNTIME_ZIP_DIR ?= tmp/runtimes/${RUNTIME_TAG}
RUNTIME_ZIP ?= ${RUNTIME_ZIP_DIR}/${RUNTIME_MACHINE}.zip

# DEBUG: Kept here for reference
# target/bundle/runtimes: target/bundle/bin
# 	mkdir target/bundle/runtimes
# 	jlink --output target/bundle/runtimes/$(MACHINE_TYPE) --add-modules java.base,java.logging,java.naming,java.xml,java.sql,java.transaction.xa,java.security.sasl,java.management

target/bundle/runtimes/%:
	mkdir -p ${RUNTIME_ZIP_DIR}
	mkdir -p target/bundle/runtimes
	[ ! -f ${RUNTIME_ZIP} ] && curl -o ${RUNTIME_ZIP} https://yet-public.s3.amazonaws.com/runtimes/refs/tags/${RUNTIME_TAG}/${RUNTIME_MACHINE_BUILD}-jre.zip || echo 'already present'
	unzip ${RUNTIME_ZIP} -d target/bundle/runtimes/
	mv target/bundle/runtimes/${RUNTIME_MACHINE_BUILD} target/bundle/runtimes/${RUNTIME_MACHINE}

target/bundle/runtimes/macos: RUNTIME_MACHINE = macos
target/bundle/runtimes/macos: RUNTIME_MACHINE_BUILD = macOS-latest

target/bundle/runtimes/linux: RUNTIME_MACHINE = linux
target/bundle/runtimes/linux: RUNTIME_MACHINE_BUILD = ubuntu-latest

target/bundle/runtimes/windows: RUNTIME_MACHINE = windows
target/bundle/runtimes/windows: RUNTIME_MACHINE_BUILD = windows-latest

target/bundle/runtimes: target/bundle/runtimes/macos target/bundle/runtimes/linux target/bundle/runtimes/windows

# Copy Windows EXEs

target/bundle/lrsql.exe: exe/lrsql.exe
	mkdir -p target/bundle
	cp exe/lrsql.exe target/bundle/lrsql.exe

target/bundle/lrsql_pg.exe: exe/lrsql_pg.exe
	mkdir -p target/bundle
	cp exe/lrsql_pg.exe target/bundle/lrsql_pg.exe

# Copy Admin UI

target/bundle/admin: resources/public/admin
	mkdir -p target/bundle
	cp -r resources/public/admin target/bundle/admin

# Create entire bundle

target/bundle: target/bundle/config target/bundle/doc target/bundle/bin target/bundle/runtimes target/bundle/lrsql.jar target/bundle/admin target/bundle/lrsql.exe target/bundle/lrsql_pg.exe

bundle: target/bundle

# *** Build Windows EXEs with launch4j ***

# `clean-exe` removes all pre-existing executables, so that they can be rebuilt.
# This is not done as part of the regular `clean` target because we do not want
# to rebuild the EXEs across multiple builds.

# To build a new set of EXEs to commit, perform the following:
# % make bundle # if you haven't built the new JAR yet
# % make clean-exe
# % make exe
# Note that `make bundle` also builds the EXEs automatically, and also copies
# them to `target/bundle`.

.phony: clean-exe

# Building the executables require launch4j to be pre-installed:
# https://stackoverflow.com/questions/5618615/check-if-a-program-exists-from-a-makefile

# The executables are assumed to be checked in and thus available to the bundle.
# BUT these targets can be used to re-generate them with the JAR if needed.

clean-exe:
	rm exe/*.exe

exe/lrsql.exe:
ifeq (,$(shell which launch4j))
	$(error "ERROR: launch4j is not installed!")
else
	launch4j exe/config.xml
endif

exe/lrsql_pg.exe:
ifeq (,$(shell which launch4j))
	$(error "ERROR: launch4j is not installed!")
else
	launch4j exe/config_pg.xml
endif

exe: exe/lrsql.exe exe/lrsql_pg.exe

# *** Run build ***

# These targets create a bundle containing a lrsql JAR and then runs
# the JAR to create the specific lrsql instance.

.phony: run-jar-h2, run-jar-sqlite, run-jar-h2-persistent, run-jar-postgres

run-jar-h2: target/bundle
	cd target/bundle; \
	LRSQL_ADMIN_USER_DEFAULT=username \
	LRSQL_ADMIN_PASS_DEFAULT=password \
	LRSQL_API_KEY_DEFAULT=username \
	LRSQL_API_SECRET_DEFAULT=password \
	bin/run_h2.sh

run-jar-h2-persistent: target/bundle
	cd target/bundle; \
	LRSQL_ADMIN_USER_DEFAULT=username \
	LRSQL_ADMIN_PASS_DEFAULT=password \
	LRSQL_API_KEY_DEFAULT=username \
	LRSQL_API_SECRET_DEFAULT=password \
	bin/run_h2_persistent.sh

run-jar-sqlite: target/bundle
	cd target/bundle; \
	LRSQL_ADMIN_USER_DEFAULT=username \
	LRSQL_ADMIN_PASS_DEFAULT=password \
	LRSQL_API_KEY_DEFAULT=username \
	LRSQL_API_SECRET_DEFAULT=password \
	bin/run_sqlite.sh

# NOTE: Requires a running Postgres instance!
run-jar-postgres: target/bundle
	cd target/bundle; \
	LRSQL_ADMIN_USER_DEFAULT=username \
	LRSQL_ADMIN_PASS_DEFAULT=password \
	LRSQL_API_KEY_DEFAULT=username \
	LRSQL_API_SECRET_DEFAULT=password \
	bin/run_postgres.sh
