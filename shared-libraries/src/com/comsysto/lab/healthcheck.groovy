def call(String url) {
    retry(5) {
		sleep time: 10, units: 'SECONDS'
		httpRequest url: '${url}', validResponseContent: '"status":"UP"'
	}
}